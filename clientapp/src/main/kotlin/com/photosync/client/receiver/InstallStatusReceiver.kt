package com.photosync.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val userIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            userIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(userIntent)
        }
    }
}
