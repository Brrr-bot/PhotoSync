package com.photosync.hub.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.photosync.hub.HubApplication
import com.photosync.hub.storage.SyncStateRepository
import com.photosync.hub.storage.UsbStorageManager
import com.photosync.hub.ui.MainActivity
import com.photosync.hub.update.UpdateChecker
import com.photosync.shared.Constants
import com.photosync.shared.model.DashboardStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HubForegroundService : LifecycleService() {

    private lateinit var discovery: UdpDiscovery
    private lateinit var handshaker: ClientHandshaker
    private lateinit var usbStorage: UsbStorageManager
    private lateinit var syncState: SyncStateRepository
    private lateinit var fileSyncer: FileSyncer

    private val activeSyncs = mutableSetOf<String>()
    private var discoveryJob: Job? = null
    private var hubAnnounceJob: Job? = null
    private var updateJob: Job? = null
    private var hubHttpServer: HubHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoSync:HubWakeLock")
            .also { it.acquire() }

        val wifiLockMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiLockMode, "PhotoSync:HubWifiLock")
            .also { it.acquire() }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        handshaker = ClientHandshaker()
        usbStorage = UsbStorageManager(this, prefs)
        syncState = SyncStateRepository(prefs)
        fileSyncer = FileSyncer(
            this, usbStorage, syncState,
            onProgress = { message ->
                log(message)
                updateNotification(message)
            },
            onTransferProgress = { current, total, filename, fileSizeBytes, sessionRemainingBytes ->
                updateProgressState(current, total, filename, fileSizeBytes)
                sendProgress(current, total, filename, fileSizeBytes, sessionRemainingBytes)
                updateNotification("$current/$total � $filename")
            },
            onFileBytes = { bytesRead, fileTotal, filename ->
                sendFileBytes(bytesRead, fileTotal, filename)
            }
        )

        discovery = UdpDiscovery(
            onClientFound = { ip, deviceName -> onClientDiscovered(ip, deviceName) },
            onError = { msg -> log("UDP error: $msg") }
        )

        hubHttpServer = HubHttpServer(
            onSyncRequest = { ip -> onClientDiscovered(ip, ip) },
            onDashboardRequest = { buildDashboardSnapshot() },
            onLog = { msg -> log(msg) }
        ).also {
            try {
                it.start()
                log("Hub HTTP server started on port ${Constants.HUB_HTTP_PORT}")
            } catch (e: Exception) {
                log("Hub HTTP server failed to start: ${e.message}")
            }
        }

        startForeground(HubApplication.NOTIFICATION_ID, buildNotification("Listening for devices�"))

        discoveryJob = lifecycleScope.launch(Dispatchers.IO) {
            discovery.run()
        }

        hubAnnounceJob = lifecycleScope.launch(Dispatchers.IO) {
            HubBroadcastAnnouncer().run()
        }

        updateJob = lifecycleScope.launch(Dispatchers.IO) {
            val checker = UpdateChecker(this@HubForegroundService)
            while (true) {
                checker.checkAndNotify()
                delay(5 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        super.onDestroy()
        discoveryJob?.cancel()
        hubAnnounceJob?.cancel()
        updateJob?.cancel()
        hubHttpServer?.stop()
        discovery.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scheduleRestart()
    }

    private fun onClientDiscovered(ip: String, deviceName: String) {
        if (ip in activeSyncs) return

        log("Heard: $deviceName ($ip)")

        lifecycleScope.launch(Dispatchers.IO) {
            activeSyncs += ip
            updateMode("Discovering")
            try {
                if (!usbStorage.isReady()) {
                    log("Found device at $ip � select a USB drive first")
                    updateNotification("Select a USB drive in the app")
                    return@launch
                }
                var result = handshaker.tryHandshake(ip, ip)
                if (result is HandshakeResult.Failure) {
                    kotlinx.coroutines.delay(4_000)
                    result = handshaker.tryHandshake(ip, ip)
                }
                if (result is HandshakeResult.Failure) {
                    log("Handshake failed for $ip: ${result.reason}")
                    return@launch
                }
                val clientInfo = (result as HandshakeResult.Success).info
                val deviceKey = clientInfo.deviceName
                syncState.setDeviceName(deviceKey, clientInfo.deviceName)

                val usbTs = usbStorage.readManifestLastSync(deviceKey)
                val prefsTs = syncState.getLastSync(deviceKey)
                if (usbTs > prefsTs) {
                    syncState.updateLastSync(deviceKey, usbTs)
                    log("Restored sync position from USB for ${clientInfo.deviceName}")
                }

                val stableInfo = clientInfo.copy(mac = deviceKey)

                updateMode("Syncing")
                log("Syncing ${clientInfo.deviceName}�")
                updateNotification("Syncing ${clientInfo.deviceName}�")
                fileSyncer.syncDevice(stableInfo)

                val finalTs = syncState.getLastSync(deviceKey)
                if (finalTs > 0) usbStorage.writeManifestLastSync(deviceKey, finalTs)
                lastSyncSummary = "Last sync: ${clientInfo.deviceName} at ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                lastUpdatedAt = System.currentTimeMillis()
            } finally {
                activeSyncs -= ip
                updateMode(if (activeSyncs.isEmpty()) "Idle" else "Busy")
            }
        }
    }

    fun sendProgress(current: Int, total: Int, filename: String, fileSizeBytes: Long = 0L, sessionRemainingBytes: Long = 0L) {
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_CURRENT, current)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_FILENAME, filename)
                .putExtra(EXTRA_FILE_SIZE, fileSizeBytes)
                .putExtra(EXTRA_SESSION_REMAINING, sessionRemainingBytes)
        )
    }

    fun sendFileBytes(bytesRead: Long, fileTotal: Long, filename: String) {
        sendBroadcast(
            Intent(ACTION_FILE_BYTES)
                .setPackage(packageName)
                .putExtra(EXTRA_BYTES_READ, bytesRead)
                .putExtra(EXTRA_FILE_TOTAL, fileTotal)
                .putExtra(EXTRA_FILENAME, filename)
        )
    }

    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$time  $message"
        lastSyncSummary = message
        lastUpdatedAt = System.currentTimeMillis()
        addLog(line)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LOG, message))
    }

    private fun updateProgressState(current: Int, total: Int, filename: String, fileSizeBytes: Long) {
        if (fileSizeBytes == 0L) {
            compressionCurrent = current
            compressionTotal = total
            if (current >= total) {
                compressionCurrent = 0
                compressionTotal = 0
            }
        } else {
            progressCurrent = current
            progressTotal = total
            currentFile = filename
            if (current >= total) {
                progressCurrent = 0
                progressTotal = 0
            }
        }
        lastUpdatedAt = System.currentTimeMillis()
    }

    private fun updateMode(mode: String) {
        currentMode = mode
        lastUpdatedAt = System.currentTimeMillis()
    }

    private fun buildDashboardSnapshot(): DashboardStatusResponse {
        val pm = getSystemService(PowerManager::class.java)
        return DashboardStatusResponse(
            hubReady = usbStorage.isReady(),
            batteryOptimizedIgnored = pm.isIgnoringBatteryOptimizations(packageName),
            accessibilityEnabled = isAccessibilityServiceEnabled(),
            currentMode = currentMode,
            progressCurrent = progressCurrent,
            progressTotal = progressTotal,
            currentFile = currentFile,
            compressionCurrent = compressionCurrent,
            compressionTotal = compressionTotal,
            recentLogs = getRecentLogs(),
            lastSyncSummary = lastSyncSummary,
            updatedAt = lastUpdatedAt
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${KeepAliveAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, HubApplication.CHANNEL_ID)
            .setContentTitle("PhotoSync Hub")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(HubApplication.NOTIFICATION_ID, buildNotification(text))
    }

    private fun scheduleRestart() {
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, HubForegroundService::class.java),
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

    companion object {
        const val PREFS_NAME = "hub_prefs"
        const val ACTION_LOG = "com.photosync.hub.LOG"
        const val EXTRA_LOG = "log_message"
        const val ACTION_PROGRESS = "com.photosync.hub.PROGRESS"
        const val EXTRA_CURRENT = "progress_current"
        const val EXTRA_TOTAL = "progress_total"
        const val EXTRA_FILENAME = "progress_filename"
        const val EXTRA_FILE_SIZE = "progress_file_size"
        const val EXTRA_SESSION_REMAINING = "progress_session_remaining"
        const val ACTION_FILE_BYTES = "com.photosync.hub.FILE_BYTES"
        const val EXTRA_BYTES_READ = "file_bytes_read"
        const val EXTRA_FILE_TOTAL = "file_bytes_total"

        private val recentLogs = ArrayDeque<String>(100)
        @Volatile private var currentMode: String = "Idle"
        @Volatile private var progressCurrent: Int = 0
        @Volatile private var progressTotal: Int = 0
        @Volatile private var currentFile: String = ""
        @Volatile private var compressionCurrent: Int = 0
        @Volatile private var compressionTotal: Int = 0
        @Volatile private var lastSyncSummary: String = "Hub service active"
        @Volatile private var lastUpdatedAt: Long = System.currentTimeMillis()

        fun getRecentLogs(): List<String> = synchronized(recentLogs) { recentLogs.toList() }

        private fun addLog(line: String) = synchronized(recentLogs) {
            if (recentLogs.size >= 100) recentLogs.removeFirst()
            recentLogs.addLast(line)
        }
    }
}
