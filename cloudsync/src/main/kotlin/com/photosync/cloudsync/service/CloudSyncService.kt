package com.photosync.cloudsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.photosync.cloudsync.CloudConfig
import com.photosync.cloudsync.auth.TokenStore
import com.photosync.cloudsync.cloud.CloudProvider
import com.photosync.cloudsync.cloud.GoogleDriveProvider
import com.photosync.cloudsync.cloud.OneDriveProvider
import com.photosync.cloudsync.server.CloudHttpServer
import com.photosync.cloudsync.sync.CloudManifest
import com.photosync.cloudsync.sync.CloudSyncEngine
import com.photosync.cloudsync.sync.SyncState
import com.photosync.cloudsync.util.RemoteLogger
import java.io.File

/** Foreground service: keeps the HTTP server alive and runs the sync engine on demand. */
class CloudSyncService : Service() {

    companion object {
        const val ACTION_START_SYNC = "com.photosync.cloudsync.START_SYNC"
        const val ACTION_STOP_SYNC  = "com.photosync.cloudsync.STOP_SYNC"
        private const val CHANNEL = "cloudsync"
        private const val NOTIF_ID = 42
        @Volatile var lastProgress: String = "Idle"
    }

    private lateinit var providers: List<CloudProvider>
    private var server: CloudHttpServer? = null
    private var engine: CloudSyncEngine? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        RemoteLogger.localLog = File(filesDir, "cloudsync_log.txt")
        val tokens = TokenStore(this)
        providers = listOf(GoogleDriveProvider(tokens), OneDriveProvider(tokens))
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val outputDir = File(baseDir, "compressed")
        val manifest = CloudManifest(File(baseDir, "manifest.json"))
        engine = CloudSyncEngine(providers, SyncState(this), manifest, outputDir, cacheDir).also { e ->
            e.onProgress = { done, ok, skipped, cur ->
                lastProgress = "Processed $done · compressed $ok · skipped $skipped · $cur"
                updateNotification(lastProgress)
            }
        }
        startServer(outputDir, manifest)
        startForegroundSafe("CloudSync ready")
    }

    private fun startServer(outputDir: File, manifest: CloudManifest) {
        try {
            server = CloudHttpServer(outputDir, manifest, providers).also { it.start() }
            RemoteLogger.i("HTTP server on :${CloudConfig.HTTP_PORT}")
        } catch (t: Throwable) { RemoteLogger.e("server start failed", t) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> startSync()
            ACTION_STOP_SYNC  -> engine?.stop()
        }
        return START_STICKY
    }

    private fun startSync() {
        val e = engine ?: return
        if (e.running) { RemoteLogger.i("sync already running"); return }
        worker = Thread { e.run() }.also { it.isDaemon = true; it.start() }
    }

    private fun startForegroundSafe(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "CloudSync", NotificationManager.IMPORTANCE_LOW))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("CloudSync").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try { server?.stop() } catch (_: Throwable) {}
        engine?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
