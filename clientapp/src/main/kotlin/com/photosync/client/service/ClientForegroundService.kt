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
import com.photosync.client.hub.HubFilesClient
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
        // Route every RemoteLogger line (service + background managers) to the in-app live card.
        RemoteLogger.onMessage = { m ->
            try { sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LOG, m)) } catch (_: Throwable) {}
        }
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
            isOnMobileData = { isMobileData() },
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
                // Persist so it survives app restarts and is available when off local network.
                // Only log when it actually changes — it fired on every handshake and spammed the feed.
                val changed = liveHubTailscaleIp != ip
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_HUB_TAILSCALE_IP, ip).apply()
                liveHubTailscaleIp = ip
                if (changed) log("Hub Tailscale IP: $ip (stored for remote sync)")
            },
            onNudge = {
                lifecycleScope.launch(Dispatchers.IO) {
                    val checker = com.photosync.client.update.UpdateChecker(this@ClientForegroundService)
                    checker.checkAndNotify()
                    checker.checkTimesheetUpdate()
                }
            }
        ).also {
            try {
                it.start()
                liveServer = it
                log("HTTP server started on port ${Constants.CLIENT_PORT}")
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

        // Video date repair runs as the first step inside VideoSpaceManager.process(),
        // which is now called in the hourly localFixJob. Also run it once at startup
        // (before the hub is available) so MP4 internal dates are patched immediately.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(5_000L)
            try {
                com.photosync.client.media.VideoSpaceManager(this@ClientForegroundService)
                    .repairCompressedVideoDates()
            } catch (t: Throwable) { log("VideoDateRepair(startup) error: ${t.message}") }
        }

        // Broadcast presence every 15s so the hub can find us
        // Pass hub's Tailscale IP so we can unicast when off the same WiFi
        announceJob = lifecycleScope.launch(Dispatchers.IO) {
            log("Starting UDP announcer on port ${Constants.DISCOVERY_PORT}")
            BroadcastAnnouncer(hubTailscaleIpProvider = { liveHubTailscaleIp }).run()
        }

        // Listen for hub announcements so we know hub's IP for phone-initiated sync
        hubDiscovery = HubDiscovery { ip, port, deviceName, tailscaleIp ->
            val wasStale = System.currentTimeMillis() - liveHubIpUpdatedAt > 90_000L
            liveHubIp = ip
            liveHubIpUpdatedAt = System.currentTimeMillis()
            liveHubPort = port
            liveHubName = deviceName
            if (tailscaleIp != null) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_HUB_TAILSCALE_IP, tailscaleIp).apply()
                liveHubTailscaleIp = tailscaleIp
            }
            // Hub just reconnected — run cleanup immediately so gallery is tidy before sync
            if (wasStale) {
                lifecycleScope.launch(Dispatchers.IO) { runOnHubConnect() }
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
                val hubFilesForLocalFix = try {
                    val ip = liveHubIp ?: liveHubTailscaleIp
                    if (ip != null) HubFilesClient.fetchFiles(ip, liveHubPort, limit = 5000)
                    else emptyList<com.photosync.client.hub.HubFileEntry>()
                } catch (_: Exception) { emptyList<com.photosync.client.hub.HubFileEntry>() }
                val fixed = processor.processUnfixed(
                    onProgress = { done, total, msg ->
                        if (done % 20 == 0 || done == total) {
                            log("LocalFix $done/$total: $msg")
                            updateNotification("Fixing: $done/$total")
                        }
                    },
                    hubFiles = hubFilesForLocalFix
                )
                if (fixed > 0) log("LocalFix complete — fixed $fixed image(s)")

                // One-time refresh of OLD poster placeholders to the new (smaller/translucent) badge.
                // Runs BEFORE the (slow) WebP pass so poster dates/badges update promptly. The hub
                // regenerates each badged poster from the original video; we overwrite the local
                // poster bytes in place and re-assert DATE_TAKEN from the filename.
                try {
                    if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_POSTER_REFRESH_V, 0) < POSTER_REFRESH_V) {
                        val n = refreshPostersOnce()
                        if (n >= 0) {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putInt(KEY_POSTER_REFRESH_V, POSTER_REFRESH_V).apply()
                            if (n > 0) log("✓ Poster refresh — $n placeholder(s) updated to new badge")
                        }
                    }
                } catch (t: Throwable) { log("Poster refresh error: ${t.javaClass.simpleName}: ${t.message}") }

                // Compress backed-up images to WebP — hub already confirmed these on USB.
                // Single compression step on the phone (no lossy hub pass first).
                try {
                    val ism = com.photosync.client.media.ImageSpaceManager(this@ClientForegroundService)
                    ism.process { done, total, _ ->
                        updateNotification("Compressing to WebP: $done/$total")
                    }  // per-file lines + summary logged inside ImageSpaceManager (RemoteLogger)
                } catch (t: Throwable) { log("ImageSpace error: ${t.javaClass.simpleName}: ${t.message}") }

                // Compress / posterise videos — same hub-confirmed safety check as images.
                // Only runs when hub is reachable (VideoSpaceManager checks live hub IP).
                try {
                    val vsm = com.photosync.client.media.VideoSpaceManager(this@ClientForegroundService)
                    vsm.process { done, total, _ ->
                        updateNotification("Video space: $done/$total")
                    }  // per-file lines + summary logged inside VideoSpaceManager (RemoteLogger)
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

    /**
     * Runs immediately when the hub reconnects (was stale > 90s).
     * Fixes dates and EXIF before any sync so the gallery is tidy before files move.
     */
    private suspend fun runOnHubConnect() {
        try {
            log("Hub connected — running pre-sync cleanup…")

            // 1. Restore files damaged by the old hub-side WebP conversion (no EXIF, wrong
            //    rotation, .jpg.webp double extension) from their pristine hub originals.
            try {
                val repair = com.photosync.client.media.GalleryRepair(this)
                val r = repair.repair { msg -> log(msg) }
                if (r.restored > 0 || r.failed > 0)
                    log("Repair done: ${r.restored} restored, ${r.failed} failed, ${r.damagedRemaining} remaining")
            } catch (t: Throwable) { log("Repair error: ${t.javaClass.simpleName}: ${t.message}") }

            // 2. Fix dates/orientation on the rest from each file's own EXIF/filename.
            //    Also pass hub file list so files with no EXIF/filename date (e.g. screenshots
            //    with descriptive names) can use the hub's lastModifiedMs as fallback.
            val hubFilesForFix = try {
                val ip = liveHubIp ?: liveHubTailscaleIp
                if (ip != null) HubFilesClient.fetchFiles(ip, liveHubPort, limit = 5000)
                else emptyList<com.photosync.client.hub.HubFileEntry>()
            } catch (_: Exception) { emptyList<com.photosync.client.hub.HubFileEntry>() }
            val processor = com.photosync.client.media.LocalImageProcessor(this)
            val fixed = processor.processUnfixed(
                onProgress = { done, total, msg ->
                    if (done % 20 == 0 || done == total) updateNotification("Cleanup: $done/$total")
                },
                hubFiles = hubFilesForFix
            )
            if (fixed > 0) log("Pre-sync cleanup: fixed $fixed image date(s)")
            else log("Pre-sync cleanup: dates OK")
            updateNotification("Ready — announcing on network")

            // Resume an interrupted "Restore metadata from hub" exactly where it stopped.
            if (com.photosync.client.media.MetadataRestorer.isIncomplete(this)) {
                log("↺ Resuming interrupted metadata restore…")
                runRestoreMetadata()
            }

            // NOTE: full hub-restore (download every hub-backed file missing from the phone)
            // is NO LONGER run automatically. Auto-restoring re-added files the phone had
            // intentionally slimmed — e.g. videos that VideoSpaceManager posterised (mp4 deleted,
            // poster kept) were re-downloaded → re-posterised → re-downloaded in an infinite loop,
            // and the hub kept seeing the re-inserted files as "new" and re-backed them up.
            // Restore is now strictly user-initiated via the menu → "Restore from hub".
        } catch (t: Throwable) { log("Pre-sync cleanup error: ${t.javaClass.simpleName}: ${t.message}") }
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
        if (intent?.action == ACTION_RESTORE_FROM_HUB) {
            lifecycleScope.launch(Dispatchers.IO) { runRestoreFromHub() }
        }
        if (intent?.action == ACTION_RESTORE_METADATA) {
            lifecycleScope.launch(Dispatchers.IO) { runRestoreMetadata() }
        }
        when (intent?.action) {
            ACTION_RUN_IMAGESPACE      -> launchDev("ImageSpace")        { runImageSpaceNow() }
            ACTION_RUN_VIDEOSPACE      -> launchDev("VideoSpace")        { runVideoSpaceNow() }
            ACTION_RUN_LOCALFIX        -> launchDev("LocalFix")          { runLocalFixNow() }
            ACTION_RUN_VIDEODATEREPAIR -> launchDev("Video date repair") { runVideoDateRepairNow() }
            ACTION_RUN_POSTERREFRESH   -> launchDev("Poster refresh")    { runPosterRefreshNow() }
            ACTION_RUN_FULLPASS        -> launchDev("Full pass")         { runFullPassNow() }
        }
        return START_STICKY
    }

    // ── Developer menu: manual one-shot triggers ───────────────────────────────
    @Volatile private var devRunning = false

    /** Runs a developer-triggered task on IO, serialised so two can't clobber MediaStore at once. */
    private fun launchDev(name: String, block: () -> Unit) {
        if (devRunning) { log("▶ Developer: busy — $name ignored (a task is already running)"); return }
        devRunning = true
        lifecycleScope.launch(Dispatchers.IO) {
            try { log("▶ Developer: running $name now…"); block() }
            catch (t: Throwable) { log("⚠ Developer $name error: ${t.javaClass.simpleName}: ${t.message}") }
            finally { devRunning = false; updateNotification("Ready") }
        }
    }

    private fun runImageSpaceNow() {
        com.photosync.client.media.ImageSpaceManager(this).process { done, total, _ ->
            updateNotification("ImageSpace: $done/$total")
        }
    }
    private fun runVideoSpaceNow() {
        com.photosync.client.media.VideoSpaceManager(this).process { done, total, _ ->
            updateNotification("VideoSpace: $done/$total")
        }
    }
    private fun runVideoDateRepairNow() {
        com.photosync.client.media.VideoSpaceManager(this).repairCompressedVideoDates()
    }
    private fun runLocalFixNow() {
        val hubFiles = try {
            val ip = liveHubIp ?: liveHubTailscaleIp
            if (ip != null) HubFilesClient.fetchFiles(ip, liveHubPort, limit = 5000)
            else emptyList<com.photosync.client.hub.HubFileEntry>()
        } catch (_: Exception) { emptyList<com.photosync.client.hub.HubFileEntry>() }
        val n = com.photosync.client.media.LocalImageProcessor(this).processUnfixed(
            onProgress = { done, total, msg -> if (done % 20 == 0 || done == total) log("LocalFix $done/$total: $msg") },
            hubFiles = hubFiles)
        log("✓ LocalFix done — $n image(s) fixed")
    }
    private fun runPosterRefreshNow() {
        val n = refreshPostersOnce()
        log("✓ Poster refresh — $n placeholder(s) updated")
    }
    private fun runFullPassNow() {
        runLocalFixNow(); runPosterRefreshNow(); runImageSpaceNow(); runVideoSpaceNow()
        log("✓ Developer: full maintenance pass complete")
    }

    @Volatile private var metaRestoreInProgress = false

    /**
     * User-initiated: re-apply full original metadata (orientation/GPS/dates/…) from the hub
     * originals onto the phone's compressed copies. Fixes rotation/date on WebP photos. Logs to the
     * live card. Waits briefly for the hub to be discovered.
     */
    private suspend fun runRestoreMetadata() {
        if (metaRestoreInProgress) { log("⟳ Metadata restore already running…"); return }
        metaRestoreInProgress = true
        try {
            var ip = liveHubIp ?: liveHubTailscaleIp
            var waited = 0
            while (ip == null && waited < 30_000) {
                if (waited == 0) log("⟳ Metadata restore: waiting for hub…")
                kotlinx.coroutines.delay(2_000); waited += 2_000
                ip = liveHubIp ?: liveHubTailscaleIp
            }
            if (ip == null) { log("⟳ Metadata restore: hub not reachable"); return }
            val port = liveHubPort
            val hub = try { HubFilesClient.fetchFiles(ip, port, limit = 20_000) } catch (e: Exception) { log("⟳ Metadata restore: hub fetch failed — ${e.message}"); return }
            if (hub.isEmpty()) { log("⟳ Metadata restore: hub returned no files — try again shortly"); return }
            log("⟳ Metadata restore: scanning compressed photos against ${hub.size} hub originals…")
            val n = com.photosync.client.media.MetadataRestorer(this).restore(ip, port, android.os.Build.MODEL, hub) { done, total, name ->
                if (done % 10 == 0 || done == total) {
                    log("⟳ Metadata $done/$total: $name")
                    updateNotification("Restoring metadata: $done/$total")
                }
            }
            log(if (n > 0) "✓ Metadata restore done — $n photo(s) re-tagged from hub originals"
                else "⟳ Metadata restore: nothing needed (no compressed photos with hub originals)")
            updateNotification("Ready — announcing on network")
        } catch (t: Throwable) {
            log("⟳ Metadata restore error: ${t.javaClass.simpleName}: ${t.message}")
        } finally { metaRestoreInProgress = false }
    }

    @Volatile private var restoreInProgress = false

    /**
     * User-initiated full restore from the hub. Runs inside the service so all progress lands on
     * the live-log card (via [log]) exactly like sync/compression do. Waits up to ~30s for the hub
     * to be discovered first, so pressing the menu item right after enabling WiFi still works.
     */
    private suspend fun runRestoreFromHub() {
        if (restoreInProgress) { log("↺ Restore already running…"); return }
        restoreInProgress = true
        try {
            // Wait for hub discovery (the menu is often tapped right after WiFi comes up).
            var ip = liveHubIp ?: liveHubTailscaleIp
            var waited = 0
            while (ip == null && waited < 30_000) {
                if (waited == 0) log("↺ Restore: waiting for hub…")
                kotlinx.coroutines.delay(2_000); waited += 2_000
                ip = liveHubIp ?: liveHubTailscaleIp
            }
            if (ip == null) { log("↺ Restore: hub not reachable — make sure both are on the hub WiFi"); return }

            val port = liveHubPort
            val allHub = try { HubFilesClient.fetchFiles(ip, port, limit = 20_000) }
                         catch (e: Exception) { log("↺ Restore: hub fetch failed — ${e.message}"); return }
            if (allHub.isEmpty()) { log("↺ Restore: hub returned no files (still warming up?) — try again shortly"); return }

            val mgr = com.photosync.client.media.HubRestoreManager(this)
            log("↺ Restore: ${allHub.size} hub files — checking what's missing…")
            val n = mgr.restoreAll(ip, port, android.os.Build.MODEL, allHub) { done, total, name ->
                if (done % 10 == 0 || done == total) {
                    log("↺ Restore $done/$total: $name")
                    updateNotification("Restoring from hub: $done/$total")
                }
            }
            mgr.markDone()
            log(if (n > 0) "↺ Restore done — $n file(s) restored; WebP/poster runs on next cycle"
                else "↺ Restore: phone already has everything on the hub")
            updateNotification("Ready — announcing on network")
        } catch (t: Throwable) {
            log("↺ Restore error: ${t.javaClass.simpleName}: ${t.message}")
        } finally { restoreInProgress = false }
    }

    /**
     * One-time: regenerate every tracked poster placeholder with the current (subtle) badge.
     * Uses the VideoSpaceManager KEY_RESTORE map ("posterName|device|videoName"); for each entry the
     * hub renders a fresh badged poster from the original video and we overwrite the local poster
     * bytes in place (same MediaStore row, name and DATE_TAKEN preserved). No whole-video download.
     * Returns the count refreshed, or -1 if the hub isn't reachable (so the caller retries later).
     */
    private fun refreshPostersOnce(): Int {
        val ip = liveHubIp ?: liveHubTailscaleIp ?: return -1
        val port = liveHubPort
        val prefs = getSharedPreferences("video_space_state", MODE_PRIVATE)
        val restore = prefs.getStringSet(com.photosync.client.media.VideoSpaceManager.KEY_RESTORE, emptySet()) ?: emptySet()
        if (restore.isEmpty()) return 0
        var done = 0
        val imgUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        for (entry in restore) {
            val parts = entry.split('|')
            if (parts.size < 3) continue
            val posterName = parts[0]; val device = parts[1]; val videoName = parts[2]

            // Read the existing poster's metadata so the replacement keeps its folder + dates.
            var id = -1L; var msDate = 0L; var rel = "DCIM/Camera/"
            contentResolver.query(
                imgUri,
                arrayOf(android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DATE_TAKEN,
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH),
                "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(posterName), null
            )?.use { if (it.moveToFirst()) { id = it.getLong(0); msDate = it.getLong(1); it.getString(2)?.let { p -> rel = p } } }
            if (id < 0) continue   // poster no longer on phone (video already restored) — skip

            // Capture date: the filename (video stem) is the most reliable source and survives an
            // earlier bad refresh that nulled DATE_TAKEN; fall back to the MediaStore value.
            val dateMs = parsePosterDate(posterName).takeIf { it > 0 } ?: msDate
            val dateSec = if (dateMs > 0) dateMs / 1000L else 0L

            val badged = com.photosync.client.hub.HubFilesClient.fetchPoster(ip, port, device, videoName) ?: continue
            val stamped = if (dateMs > 0) com.photosync.client.media.VideoThumbnailer.stampPosterExif(badged, dateMs) else badged

            // In-place byte overwrite NULLS DATE_TAKEN on Samsung (a plain update/scan won't restore
            // it). The only reliable way to set the date is delete + re-insert with the IS_PENDING
            // dance + double date-update — the same pattern insertPoster/saveToGallery use.
            try {
                contentResolver.delete(android.content.ContentUris.withAppendedId(imgUri, id), null, null)
            } catch (_: Exception) { continue }
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, posterName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, rel)
                    if (dateMs > 0) put(android.provider.MediaStore.Images.Media.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(android.provider.MediaStore.Images.Media.DATE_ADDED, dateSec); put(android.provider.MediaStore.Images.Media.DATE_MODIFIED, dateSec) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val nu = contentResolver.insert(imgUri, values) ?: continue
                contentResolver.openOutputStream(nu)?.use { it.write(stamped) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.update(nu, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        put(android.provider.MediaStore.Images.Media.SIZE, stamped.size.toLong())
                        if (dateMs > 0) put(android.provider.MediaStore.Images.Media.DATE_TAKEN, dateMs)
                    }, null, null)
                    // Samsung resets dates during IS_PENDING→0 — force them again.
                    if (dateMs > 0) contentResolver.update(nu, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DATE_TAKEN, dateMs)
                        if (dateSec > 0) { put(android.provider.MediaStore.Images.Media.DATE_ADDED, dateSec); put(android.provider.MediaStore.Images.Media.DATE_MODIFIED, dateSec) }
                    }, null, null)
                }
                done++
            } catch (_: Exception) {}
        }
        return done
    }

    /** Parses a capture date (epoch ms) from a poster/video filename, or 0 if none. Handles
     *  YYYYMMDD_HHMMSS, YYYYMMDD / YYYY-MM-DD, and a 13-digit epoch embedded in the name. */
    private fun parsePosterDate(name: String): Long {
        val stem = name.substringBeforeLast('.')
        val now = System.currentTimeMillis()
        val since2000 = 946_684_800_000L
        fun ok(ms: Long) = if (ms in since2000..now) ms else 0L
        Regex("""(\d{8})[_-](\d{6})""").find(stem)?.let { m ->
            runCatching {
                java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { isLenient = false }
                    .parse(m.groupValues[1] + m.groupValues[2])?.time
            }.getOrNull()?.let { if (ok(it) > 0) return it }
        }
        Regex("""(\d{13})""").find(stem)?.let { m ->
            m.groupValues[1].toLongOrNull()?.let { if (ok(it) > 0) return it }
        }
        Regex("""(\d{4})-?(\d{2})-?(\d{2})""").find(stem)?.let { m ->
            runCatching {
                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { isLenient = false }
                    .parse(m.groupValues[1] + m.groupValues[2] + m.groupValues[3])?.time
            }.getOrNull()?.let { if (ok(it) > 0) return it }
        }
        return 0L
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
        // Broadcast to the in-app card happens for ALL lines via RemoteLogger.onMessage (set in
        // onCreate), so just forward — this avoids double-broadcasting service log() lines.
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
        val ip   = liveHubIp ?: liveHubTailscaleIp ?: return
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
        const val ACTION_RESTORE_FROM_HUB = "com.photosync.client.RESTORE_FROM_HUB"
        const val ACTION_RESTORE_METADATA = "com.photosync.client.RESTORE_METADATA"
        // Developer menu — manual one-shot triggers for the background maintenance functions.
        const val ACTION_RUN_IMAGESPACE      = "com.photosync.client.RUN_IMAGESPACE"
        const val ACTION_RUN_VIDEOSPACE      = "com.photosync.client.RUN_VIDEOSPACE"
        const val ACTION_RUN_LOCALFIX        = "com.photosync.client.RUN_LOCALFIX"
        const val ACTION_RUN_VIDEODATEREPAIR = "com.photosync.client.RUN_VIDEODATEREPAIR"
        const val ACTION_RUN_POSTERREFRESH   = "com.photosync.client.RUN_POSTERREFRESH"
        const val ACTION_RUN_FULLPASS        = "com.photosync.client.RUN_FULLPASS"
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
        private const val LOCAL_FIX_CODE         = 19 // bump when scan logic changes to force rescan
        private const val KEY_POSTER_REFRESH_V   = "poster_refresh_version"
        private const val POSTER_REFRESH_V       = 4  // v4: play badge moved to bottom-right corner (was centred)

        private val recentLogs = ArrayDeque<String>(100)

        fun getRecentLogs(): List<String> = synchronized(recentLogs) { recentLogs.toList() }

        /** Log from a context where there is no service instance (e.g. MainActivity helpers). */
        fun staticLog(message: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            addLog("$time  $message")
            RemoteLogger.i(message)
        }

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
