package com.photosync.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.photosync.client.util.RemoteLogger
import java.io.File

class ClientApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Local log mirrors device_logs.txt — read by the on-device HTTP endpoints
        RemoteLogger.localLog = File(filesDir, "teaching_log.txt")
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PhotoSync Client", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "File server status" }
        )
        nm.createNotificationChannel(
            NotificationChannel(DELETE_CHANNEL_ID, "PhotoSync — Delete originals",
                NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Tap to approve deletion of original photos after compression" }
        )
        nm.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, "PhotoSync Updates",
                NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "App update available" }
        )
        nm.createNotificationChannel(
            NotificationChannel(TAILSCALE_CHANNEL_ID, "PhotoSync — Tailscale",
                NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tailscale connection reminder" }
        )
    }

    companion object {
        const val CHANNEL_ID              = "photosync_client"
        const val DELETE_CHANNEL_ID       = "photosync_delete"
        const val UPDATE_CHANNEL_ID       = "photosync_client_update"
        const val TAILSCALE_CHANNEL_ID    = "photosync_tailscale"
        const val NOTIFICATION_ID         = 2001
        const val DELETE_NOTIFICATION_ID  = 2002
        const val UPDATE_NOTIFICATION_ID  = 2003
        const val TAILSCALE_NOTIFICATION_ID = 2004
    }
}
