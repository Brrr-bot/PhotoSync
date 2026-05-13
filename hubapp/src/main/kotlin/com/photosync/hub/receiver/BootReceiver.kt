package com.photosync.hub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photosync.hub.service.HubForegroundService
import com.photosync.hub.ui.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startForegroundService(Intent(context, HubForegroundService::class.java))
        }
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
