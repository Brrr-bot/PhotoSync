package com.photosync.hub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class HubApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "PhotoSync Hub", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Backup status notifications" })
        nm.createNotificationChannel(NotificationChannel(
            UPDATE_CHANNEL_ID, "PhotoSync Updates", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "App update available" })
        nm.createNotificationChannel(NotificationChannel(
            TAILSCALE_CHANNEL_ID, "PhotoSync — Tailscale", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Tailscale connection reminder" })
    }

    companion object {
        const val CHANNEL_ID              = "photosync_hub"
        const val NOTIFICATION_ID         = 1001
        const val UPDATE_CHANNEL_ID       = "photosync_hub_update"
        const val UPDATE_NOTIFICATION_ID  = 1003
        const val TAILSCALE_CHANNEL_ID    = "photosync_hub_tailscale"
        const val TAILSCALE_NOTIFICATION_ID = 1004
    }
}
