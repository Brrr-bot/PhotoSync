package com.photosync.client.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.photosync.client.R
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.service.ClientForegroundService
import java.io.File

/**
 * Invisible trampoline launched via ACTION_SEND share target.
 * Shows a dialog letting the user choose:
 *  - Share via Hub      — downloads original from hub, shows progress notification,
 *                         notification action becomes "Share" when ready
 *  - Download Original  — replaces the local compressed/placeholder with the hub original,
 *                         shows progress notification, marks file as restored
 */
class ShareViaHubActivity : AppCompatActivity() {

    private lateinit var nm: NotificationManager
    private var notifId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nm = getSystemService(NotificationManager::class.java)
        createChannel()

        val incomingUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (incomingUri == null) { finish(); return }

        val fileName = resolveDisplayName(incomingUri)
        if (fileName == null) {
            Toast.makeText(this, "Could not read file name", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        notifId = fileName.hashCode()

        AlertDialog.Builder(this)
            .setTitle(fileName)
            .setMessage("What would you like to do?")
            .setPositiveButton("Share via Hub")    { _, _ -> doShare(fileName) }
            .setNeutralButton("Download Original") { _, _ -> doDownloadOriginal(fileName) }
            .setNegativeButton("Cancel")           { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ── Hub IP resolution ─────────────────────────────────────────────────

    private fun resolveHubIp(): String? {
        val localFresh = System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L
        ClientForegroundService.liveHubIp?.takeIf { localFresh }?.let { return it }
        ClientForegroundService.liveHubTailscaleIp?.let { return it }
        return getSharedPreferences("client_prefs", MODE_PRIVATE).getString("hub_tailscale_ip", null)
    }

    // ── Share via Hub ─────────────────────────────────────────────────────

    private fun doShare(fileName: String) {
        val ip = resolveHubIp() ?: run {
            Toast.makeText(this, "Hub not reachable — start PhotoSync first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val port = ClientForegroundService.liveHubPort
        postProgress(fileName, 0, 0, isShare = true)
        finish()  // activity closes; thread keeps running, notification takes over

        Thread {
            val match = findOnHub(ip, port, fileName) ?: run {
                postError(fileName, "\"$fileName\" not found on hub")
                return@Thread
            }

            // share_tmp lives inside cacheDir (internal storage) — never scanned by MediaStore/Gallery
            val shareDir = File(cacheDir, "share_tmp").also { it.mkdirs() }
            // Clean up leftover files older than 10 min from previous share ops
            shareDir.listFiles()?.forEach { f ->
                if (System.currentTimeMillis() - f.lastModified() > 10 * 60 * 1000L) f.delete()
            }
            val tmpFile = File(shareDir, match.displayName)
            var lastShareProgressMs = 0L
            val ok = HubFilesClient.fetchFileToFile(ip, port, match.deviceName, match.displayName, tmpFile) { read, total ->
                val now = System.currentTimeMillis()
                if (now - lastShareProgressMs >= 250L) {
                    lastShareProgressMs = now
                    postProgress(fileName, read, total, isShare = true)
                }
            }
            if (!ok) { tmpFile.delete(); postError(fileName, "Download failed"); return@Thread }

            val mime     = mimeFor(match.displayName)
            val shareUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)

            val shareIntent = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }, "Share ${match.displayName}"
            ).also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            val pi = PendingIntent.getActivity(
                this, notifId, shareIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            postDone(fileName, "Ready — tap to share", pi, actionLabel = "Share")

            // Delete the temp file 5 minutes after posting the share notification —
            // gives any share-target app enough time to read it.
            Thread {
                Thread.sleep(5 * 60 * 1000L)
                tmpFile.delete()
            }.apply { isDaemon = true; start() }
        }.start()
    }

    // ── Download Original ─────────────────────────────────────────────────

    private fun doDownloadOriginal(fileName: String) {
        val ip = resolveHubIp() ?: run {
            Toast.makeText(this, "Hub not reachable — start PhotoSync first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val port = ClientForegroundService.liveHubPort
        postProgress(fileName, 0, 0, isShare = false)
        finish()

        Thread {
            val match = findOnHub(ip, port, fileName) ?: run {
                postError(fileName, "\"$fileName\" not found on hub")
                return@Thread
            }

            val tmpFile = File(cacheDir, "restore_${match.displayName}")
            var lastDlProgressMs = 0L
            val ok = HubFilesClient.fetchFileToFile(ip, port, match.deviceName, match.displayName, tmpFile) { read, total ->
                val now = System.currentTimeMillis()
                if (now - lastDlProgressMs >= 250L) {
                    lastDlProgressMs = now
                    postProgress(fileName, read, total, isShare = false)
                }
            }
            if (!ok || tmpFile.length() == 0L) {
                tmpFile.delete()
                postError(fileName, "Download failed")
                return@Thread
            }

            val bytes = tmpFile.readBytes()
            tmpFile.delete()
            val mime = mimeFor(fileName)

            val mediaStore = MediaStoreHelper(this)
            val localEntry = mediaStore.getMediaSince(0).firstOrNull { it.displayName == fileName }

            val restored = if (localEntry != null) {
                try { mediaStore.replaceFile(localEntry.id, mime, bytes, localEntry.dateTaken); true }
                catch (_: Exception) { false }
            } else {
                saveToGallery(fileName, bytes, mime)
            }

            if (restored) {
                val prefs = getSharedPreferences("compression_state", MODE_PRIVATE)
                val names = prefs.getStringSet("restored_original_names", emptySet())!!.toMutableSet()
                names.add(fileName)
                prefs.edit().putStringSet("restored_original_names", names).apply()

                val spacePrefs = getSharedPreferences("make_space_state", MODE_PRIVATE)
                val processed = spacePrefs.getStringSet("processed_names", emptySet())!!.toMutableSet()
                processed.remove(fileName)
                spacePrefs.edit().putStringSet("processed_names", processed).apply()
            }

            val dismissIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, notifId + 1, dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (restored) postDone(fileName, "Original saved to phone", pi, actionLabel = "Open App")
            else postError(fileName, "Failed to save file")
        }.start()
    }

    // ── Notification helpers ──────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hub Downloads",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for downloads from the hub"
                    setSound(null, null)
                }
            )
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L     -> "%.0f KB".format(bytes / 1_024.0)
        else                -> "$bytes B"
    }

    /** Update the in-progress notification (call at most every 250 ms). */
    private fun postProgress(fileName: String, read: Long, total: Long, isShare: Boolean) {
        val title    = if (isShare) "Fetching: $fileName" else "Downloading: $fileName"
        val progress = if (total > 0) ((read * 100L) / total).toInt() else 0
        val text     = if (total > 0) "${formatBytes(read)} / ${formatBytes(total)}"
                       else if (read > 0) formatBytes(read) else "Connecting…"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        if (total > 0) builder.setProgress(100, progress, false)
        else           builder.setProgress(0, 0, true)

        nm.notify(notifId, builder.build())
    }

    /** Final notification with an active action button. */
    private fun postDone(fileName: String, text: String, actionPi: PendingIntent, actionLabel: String) {
        // Cancel the ongoing progress notification first — on some Android versions
        // an ongoing:true notification won't cleanly transition to ongoing:false via
        // a same-ID notify() call.
        nm.cancel(notifId)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .addAction(android.R.drawable.ic_menu_share, actionLabel, actionPi)
            .setContentIntent(actionPi)
        nm.notify(notifId, builder.build())
    }

    /** Error notification. */
    private fun postError(fileName: String, msg: String) {
        nm.cancel(notifId)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(fileName)
            .setContentText(msg)
            .setOngoing(false)
            .setAutoCancel(true)
        nm.notify(notifId, builder.build())
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun findOnHub(ip: String, port: Int, fileName: String) =
        try {
            val files = HubFilesClient.fetchFiles(ip, port, limit = 10_000)
            files.firstOrNull { it.displayName == fileName }
                ?: run {
                    // Placeholder JPEGs share a base name with the original video.
                    // If the .jpg is not on the hub, look for the same base name with a
                    // video extension so the user can still download/share the original.
                    if (fileName.endsWith(".jpg", ignoreCase = true) ||
                        fileName.endsWith(".jpeg", ignoreCase = true)) {
                        val base = fileName.substringBeforeLast('.')
                        files.firstOrNull { it.displayName.substringBeforeLast('.') == base
                            && it.displayName.substringAfterLast('.').lowercase()
                               .let { ext -> ext == "mp4" || ext == "mov" || ext == "mkv" || ext == "avi" } }
                    } else null
                }
        } catch (_: Exception) { null }

    private fun saveToGallery(name: String, bytes: ByteArray, mime: String): Boolean {
        return try {
            val isVideo = mime.startsWith("video/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) Environment.DIRECTORY_MOVIES + "/PhotoSync"
                        else Environment.DIRECTORY_PICTURES + "/PhotoSync")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            true
        } catch (_: Exception) { false }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) return cursor.getString(col)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "webp"        -> "image/webp"
        "mp4"         -> "video/mp4"
        "mov"         -> "video/quicktime"
        else          -> "image/jpeg"
    }

    companion object {
        const val CHANNEL_ID = "hub_download_channel"
    }
}
