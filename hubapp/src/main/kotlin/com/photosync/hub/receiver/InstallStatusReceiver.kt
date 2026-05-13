package com.photosync.hub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val userIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                userIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(userIntent)
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                context.getSharedPreferences("update_checker", Context.MODE_PRIVATE)
                    .edit().remove("pending_install_version").apply()
            }
        }
    }
}
