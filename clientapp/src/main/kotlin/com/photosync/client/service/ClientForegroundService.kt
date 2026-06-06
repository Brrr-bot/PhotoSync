package com.photosync.client.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.photosync.client.BuildConfig
import com.photosync.client.ClientApplication
import com.photosync.client.media.LocalImageProcessor
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.ui.MainActivity
import com.photosync.client.update.UpdateChecker
import com.photosync.client.util.RemoteLogger
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

class ClientForegroundService : LifecycleService() {

    private var server: MediaHttpServer? = null
    private var announceJob: Job? = null
    private var hubDiscoveryJob: Job? = null
    private var updateJob: Job? = null
    private var localFixJob: Job? = null
    private var hubDiscovery: HubDiscovery? = null
    private var deleteNotificationShown = false

    // ── Periodic sync heartbeat ───────────────────────────────────────────────
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            autoTriggerHubSync()
            syncHandler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    // Keep CPU alive so NanoHTTPD threads can accept connections when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    // Keep WiFi radio active so incoming TCP connections aren't dropped
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        // Restore persisted Tailscale IP so remote sync works immediately after restart
        liveHubTailscaleIp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_HUB_TAILSCALE_IP, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ClientApplication.NOTIFICATION_ID, buildNotification("Starting…"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ClientApplication.NOTIFICATION_ID, buildNotification())
        }

        // Acquire locks before starting the server
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoSync:ClientWakeLock")
            .also { it.acquire() }

        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiLockMode, "PhotoSync:ClientWifiLock")
            .also { it.acquire() }

        // Start HTTP server
        server = MediaHttpServer(
            MediaStoreHelper(this),
            cacheDir = cacheDir,
            onLog = { msg ->
                log(msg)
                updateNotification(msg)
            },
            onPendingDeletes = {
                if (!deleteNotificationShown) {
                    deleteNotificationShown = true
                    postDeleteNotification()
                }
            },
            onHubTailscaleIp = { ip ->
                // Persist so it survives app restarts and is available when off local network
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_HUB_TAILSCALE_IP, ip).apply()
                liveHubTailscaleIp = ip
                log("Hub Tailscale IP: $ip (stored for remote sync)")
            },
            onNudge = {
                lifecycleScope.launch(Dispatchers.IO) {
                    val checker = com.photosync.client.update.UpdateChecker(this@ClientForegroundService)
                    checker.checkAndNotify()
                    checker.checkTimesheetUpdate()
                }
            },
            deletedNames = {
                getSharedPreferences("deletion_state", MODE_PRIVATE)
                    .getStringSet("user_deleted_names", emptySet()) ?: emptySet()
            }
        ).also {
            try {
                it.start()
                liveServer = it
                log("HTTP server started on port ${Constants.CLIENT_PORT}")
                RemoteLogger.i("Started v${com.photosync.client.BuildConfig.VERSION_NAME} (build ${com.photosync.client.BuildConfig.VERSION_CODE})")
            } catch (e: Exception) {
                log("ERROR: server failed to start — ${e.message}")
            }
        }

        // Auto-cleanup orphaned originals on startup. Old compression runs (v37 and
        // earlier) created "(1)" duplicates while leaving the originals in place; this
        // sweep deletes any original whose smaller compressed twin already exists, then
        // renames stranded "(1)" files back to the clean name. Runs once per startup.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val helper = MediaStoreHelper(this@ClientForegroundService)
                val n = helper.cleanupOrphanedOriginals()
                if (n > 0) log("Auto-cleanup deleted $n orphaned original(s)")
            } catch (e: Exception) {
                log("Auto-cleanup failed: ${e.message}")
            }
        }

        // Broadcast presence every 15s so the hub can find us
        // Pass hub's Tailscale IP so we can unicast when off the same WiFi
        announceJob = lifecycleScope.launch(Dispatchers.IO) {
            log("Starting UDP announcer on port ${Constants.DISCOVERY_PORT}")
            BroadcastAnnouncer(hubTailscaleIpProvider = { liveHubTailscaleIp }).run()
        }

        // Listen for hub announcements so we know hub's IP for phone-initiated sync
        hubDiscovery = HubDiscovery { ip, port, deviceName, tailscaleIp ->
            liveHubIp = ip
            liveHubIpUpdatedAt = System.currentTimeMillis()
            liveHubPort = port
            liveHubName = deviceName
            if (tailscaleIp != null) {
                // Persist hub's Tailscale IP so remote sync works after leaving local network
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_HUB_TAILSCALE_IP, tailscaleIp).apply()
                liveHubTailscaleIp = tailscaleIp
            }
        }.also { discovery ->
            hubDiscoveryJob = lifecycleScope.launch(Dispatchers.IO) {
                discovery.run()
            }
        }

        // Start periodic heartbeat — triggers hub sync every 5 min automatically
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)

        // Check for app updates every 30 seconds
        updateJob = lifecycleScope.launch(Dispatchers.IO) {
            val checker = UpdateChecker(this@ClientForegroundService)
            while (true) {
                checker.checkAndNotify()
                checker.checkTimesheetUpdate()
                delay(30_000L)
            }
        }

        // Fix orientation + missing DATE_TAKEN locally — runs on startup then every hour.
        // Scans all images not yet checked; uses replaceFile() for the same INSERT+DELETE
        // pattern so fixed files are excluded from future hub syncs automatically.
        localFixJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(15_000L) // let server settle first
            while (true) {
                val processor = LocalImageProcessor(this@ClientForegroundService)
                // v3+: filename-based date parsing added — clear stale checked IDs once so
                // files previously marked "fine" get re-evaluated with the new logic.
                if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getInt(KEY_LOCAL_FIX_VERSION, 0) < LOCAL_FIX_CODE) {
                    processor.clearCheckedIds()
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt(KEY_LOCAL_FIX_VERSION, LOCAL_FIX_CODE).apply()
                    log("LocalFix: cleared stale scan cache for v$LOCAL_FIX_CODE rescan")
                }
                val fixed = processor.processUnfixed(onProgress = { done, total, msg ->
                    if (done % 20 == 0 || done == total) {
                        log("LocalFix $done/$total: $msg")
                        updateNotification("Fixing: $done/$total")
                    }
                })
                if (fixed > 0) log("LocalFix complete — fixed $fixed image(s)")

                // Video space management — only touches videos the hub already holds.
                try {
                    val vsm = com.photosync.client.media.VideoSpaceManager(this@ClientForegroundService)
                    val vs = vsm.process { done, total, _ ->
                        if (done % 5 == 0 || done == total) updateNotification("Video space: $done/$total")
                    }
                    if (vs.thumbed > 0 || vs.compressed > 0 || vs.skipped > 0)
                        log("VideoSpace: ${vs.thumbed} posterised, ${vs.compressed} compressed, " +
                            "${vs.freedBytes / 1_048_576}MB freed (${vs.skipped} skipped)")
                } catch (t: Throwable) { log("VideoSpace error: ${t.javaClass.simpleName}: ${t.message}") }

                updateNotification("Ready — announcing on network")
                delay(LOCAL_FIX_INTERVAL_MS)
            }
        }

        // After a short delay, check if Tailscale is connected. If not, post a tappable
        // notification that opens the Tailscale app so the user can connect with one tap.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(20_000L)
            if (com.photosync.client.network.TailscaleIpDetector.getIp() == null) {
                postTailscaleNotification()
            }
        }

        updateNotification("Ready — announcing on network")
    }

    private fun postTailscaleNotification() {
        val tailscaleIntent = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            ?: return  // Tailscale not installed — nothing to do
        val pi = android.app.PendingIntent.getActivity(
            this, 0, tailscaleIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, ClientApplication.TAILSCALE_CHANNEL_ID)
            .setContentTitle("Tailscale not connected")
            .setContentText("Tap to open Tailscale and connect for remote sync")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            .notify(ClientApplication.TAILSCALE_NOTIFICATION_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacks(syncRunnable)
        announceJob?.cancel()
        hubDiscoveryJob?.cancel()
        localFixJob?.cancel()
        hubDiscovery?.stop()
        server?.stop()
        liveServer = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scheduleRestart()
    }

    /**
     * Posts a high-priority notification the user can tap to open [AutoDeleteActivity].
     * Safe on all Android versions — notifications launching activities are always permitted.
     */
    private fun postDeleteNotification() {
        val tapIntent = android.app.PendingIntent.getActivity(
            this, 0,
            android.content.Intent(this, com.photosync.client.ui.AutoDeleteActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, ClientApplication.DELETE_CHANNEL_ID)
            .setContentTitle("PhotoSync — tap to free space")
            .setContentText("Originals are queued for deletion. Tap to approve.")
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            .notify(ClientApplication.DELETE_NOTIFICATION_ID, notif)
    }

    fun dismissDeleteNotification() {
        getSystemService(android.app.NotificationManager::class.java)
            .cancel(ClientApplication.DELETE_NOTIFICATION_ID)
        deleteNotificationShown = false
    }

    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$time  $message"
        addLog(line)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LOG, message))
        RemoteLogger.i(message)
        refreshNotification()
    }

    private fun updateNotification(text: String) {
        // Just refresh — the notification reads from the log buffer which log() maintains
        refreshNotification()
    }

    private fun refreshNotification() {
        try {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(ClientApplication.NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun buildNotification(startupText: String? = null): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val lines   = getRecentLogs().takeLast(LOG_LINES_IN_NOTIF)
        val latest  = lines.lastOrNull() ?: startupText ?: "Starting…"
        val version = "v${BuildConfig.VERSION_NAME}"

        return NotificationCompat.Builder(this, ClientApplication.CHANNEL_ID)
            .setContentTitle("PhotoSync Client  $version")
            .setContentText(latest)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setColor(0xFF00897B.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("PhotoSync Client  $version")
                    .bigText(lines.joinToString("\n"))
            )
            .build()
    }

    // ── Auto sync trigger ─────────────────────────────────────────────────────

    /**
     * POSTs /sync to the hub (local IP preferred, Tailscale fallback).
     * On local WiFi the hub already auto-pulls from UDP announcements;
     * this heartbeat ensures sync still happens remotely or after missed packets.
     * The hub's activeSyncs guard prevents double-syncing if already in progress.
     */
    private fun isMobileData(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun autoTriggerHubSync() {
        if (isMobileData() && !getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ALLOW_MOBILE_DATA, false)) return
        // Prefer fresh LAN IP; fall back to Tailscale when off the same network (or stale >90s)
        val ip = liveHubIp
            ?.takeIf { System.currentTimeMillis() - liveHubIpUpdatedAt < 90_000L }
            ?: liveHubTailscaleIp
            ?: liveHubIp  // stale LAN IP — best we have
            ?: return
        val port = liveHubPort
        Thread {
            try {
                val ts     = System.currentTimeMillis()
                val device = Build.MODEL
                val hmac   = HmacAuth.sign(HmacAuth.buildPayload(ts, device))
                val conn   = URL("http://$ip:$port${Constants.PATH_SYNC}").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty(Constants.HEADER_HMAC,      hmac)
                conn.setRequestProperty(Constants.HEADER_TIMESTAMP,  ts.toString())
                conn.setRequestProperty(Constants.HEADER_DEVICE,     device)
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                conn.doOutput = true
                conn.outputStream.close()
                conn.responseCode   // send the request
                conn.disconnect()
            } catch (_: Exception) {
                // Hub unreachable — will retry at next interval
            }
        }.start()
    }

    companion object {
        const val ACTION_LOG = "com.photosync.client.LOG"
        const val EXTRA_LOG = "log_message"

        /** Exposed so MainActivity can poll progress state without broadcasts. */
        @Volatile var liveServer: MediaHttpServer? = null

        /** Last hub seen via UDP broadcast — used for phone-initiated sync on local network. */
        @Volatile var liveHubIp: String? = null
        @Volatile var liveHubIpUpdatedAt: Long = 0L   // epoch ms; stale after 90s
        @Volatile var liveHubPort: Int = Constants.HUB_HTTP_PORT
        @Volatile var liveHubName: String? = null

        /** Hub's Tailscale IP — persisted across restarts, used for remote sync off local network. */
        @Volatile var liveHubTailscaleIp: String? = null

        const val PREFS_NAME = "client_prefs"
        const val KEY_HUB_TAILSCALE_IP = "hub_tailscale_ip"
        const val KEY_ALLOW_MOBILE_DATA = "allow_mobile_data"
        private const val LOG_LINES_IN_NOTIF    = 6
        private const val SYNC_INTERVAL_MS      = 5 * 60 * 1000L
        private const val LOCAL_FIX_INTERVAL_MS  = 60 * 60 * 1000L
        private const val KEY_LOCAL_FIX_VERSION  = "local_fix_version"
        private const val LOCAL_FIX_CODE         = 10 // bump when scan logic changes to force rescan

        private val recentLogs = ArrayDeque<String>(100)

        fun getRecentLogs(): List<String> = synchronized(recentLogs) { recentLogs.toList() }

        private fun addLog(line: String) = synchronized(recentLogs) {
            if (recentLogs.size >= 100) recentLogs.removeFirst()
            recentLogs.addLast(line)
        }
    }

    private fun scheduleRestart() {
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, ClientForegroundService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(android.app.AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + 5_000
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}
