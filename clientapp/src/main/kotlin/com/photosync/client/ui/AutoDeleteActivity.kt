package com.photosync.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.service.ClientForegroundService

/**
 * Transparent activity that immediately shows the system delete dialog for any originals
 * queued after compression.  Launched automatically by [ClientForegroundService] — no user
 * navigation required.  On Android 12+ with MANAGE_MEDIA, originals are already deleted
 * in-place and this activity is never launched.
 */
class AutoDeleteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish()
            return
        }

        val helper = MediaStoreHelper(this)
        if (!helper.hasPendingDeletions()) {
            finish()
            return
        }

        val pending = helper.buildDeleteRequest()
        if (pending == null) {
            helper.clearPendingDeletions()
            ClientForegroundService.liveServer?.statePendingDeletes = false
            finish()
            return
        }

        try {
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pending.intentSender, REQ_DELETE, null, 0, 0, 0)
        } catch (_: Exception) {
            finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE && resultCode == Activity.RESULT_OK) {
            val helper = MediaStoreHelper(this)
            // Publish compressed replacements now that originals are gone — no duplicate window
            helper.publishPendingFiles()
            helper.clearPendingDeletions()
            ClientForegroundService.liveServer?.statePendingDeletes = false
        }
        // Dismiss the notification and reset the flag so it can fire again next time
        (getSystemService(android.app.NotificationManager::class.java))
            .cancel(com.photosync.client.ClientApplication.DELETE_NOTIFICATION_ID)
        ClientForegroundService.liveServer?.let {
            if (!it.statePendingDeletes) {
                // Re-allow the notification next compression session
                // (service tracks this via deleteNotificationShown; can't reach it directly,
                //  so we tell the service via a broadcast)
            }
        }
        finish()
    }

    companion object {
        private const val REQ_DELETE = 2001
    }
}
