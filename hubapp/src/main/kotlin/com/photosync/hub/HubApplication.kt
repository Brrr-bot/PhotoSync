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
    }

    companion object {
        const val CHANNEL_ID           = "photosync_hub"
        const val NOTIFICATION_ID      = 1001
        const val UPDATE_CHANNEL_ID    = "photosync_hub_update"
        const val UPDATE_NOTIFICATION_ID = 1003
    }
}
