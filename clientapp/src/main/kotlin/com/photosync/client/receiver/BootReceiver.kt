package com.photosync.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photosync.client.service.ClientForegroundService
import com.photosync.client.ui.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startForegroundService(Intent(context, ClientForegroundService::class.java))
        }
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
