package com.photosync.hub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photosync.hub.service.HubForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, HubForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
