package com.photosync.client.ui

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
import androidx.core.content.FileProvider
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.service.ClientForegroundService
import java.io.File

/**
 * Invisible trampoline launched via ACTION_SEND share target.
 * Shows a dialog letting the user choose:
 *  - Share via Hub      — downloads original from hub and re-shares via system share sheet
 *  - Download Original  — replaces the local compressed/placeholder with the hub original
 */
class ShareViaHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (incomingUri == null) { finish(); return }

        val fileName = resolveDisplayName(incomingUri)
        if (fileName == null) {
            Toast.makeText(this, "Could not read file name", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        AlertDialog.Builder(this)
            .setTitle(fileName)
            .setMessage("What would you like to do?")
            .setPositiveButton("Share via Hub") { _, _ -> doShare(fileName) }
            .setNeutralButton("Download Original") { _, _ -> doDownloadOriginal(fileName) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Best hub IP: live LAN (fresh) -> live Tailscale -> stored Tailscale in prefs. */
    private fun resolveHubIp(): String? {
        val localFresh = System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L
        ClientForegroundService.liveHubIp?.takeIf { localFresh }?.let { return it }
        ClientForegroundService.liveHubTailscaleIp?.let { return it }
        return getSharedPreferences("client_prefs", MODE_PRIVATE).getString("hub_tailscale_ip", null)
    }

    private fun doShare(fileName: String) {
        val ip = resolveHubIp() ?: run {
            Toast.makeText(this, "Hub not reachable — start PhotoSync first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val port = ClientForegroundService.liveHubPort
        Toast.makeText(this, "Fetching original from hub…", Toast.LENGTH_SHORT).show()

        Thread {
            val match = findOnHub(ip, port, fileName) ?: run {
                runOnUiThread {
                    Toast.makeText(this, "\"$fileName\" not found on hub", Toast.LENGTH_LONG).show()
                    finish()
                }; return@Thread
            }

            val tmpFile = File(cacheDir, match.displayName)
            if (!HubFilesClient.fetchFileToFile(ip, port, match.deviceName, match.displayName, tmpFile)) {
                runOnUiThread {
                    Toast.makeText(this, "Download from hub failed", Toast.LENGTH_SHORT).show()
                    finish()
                }; return@Thread
            }

            val mime = mimeFor(match.displayName)
            val shareUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)
            runOnUiThread {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share ${match.displayName}"
                ))
                finish()
            }
        }.start()
    }

    private fun doDownloadOriginal(fileName: String) {
        val ip = resolveHubIp() ?: run {
            Toast.makeText(this, "Hub not reachable — start PhotoSync first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val port = ClientForegroundService.liveHubPort
        Toast.makeText(this, "Downloading original from hub…", Toast.LENGTH_SHORT).show()

        Thread {
            val match = findOnHub(ip, port, fileName) ?: run {
                runOnUiThread {
                    Toast.makeText(this, "\"$fileName\" not found on hub", Toast.LENGTH_LONG).show()
                    finish()
                }; return@Thread
            }

            val tmpFile = File(cacheDir, "restore_${match.displayName}")
            if (!HubFilesClient.fetchFileToFile(ip, port, match.deviceName, match.displayName, tmpFile)
                    || tmpFile.length() == 0L) {
                tmpFile.delete()
                runOnUiThread {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
                    finish()
                }; return@Thread
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
                // Mark restored so Make Space + fix sweeps skip this file
                val prefs = getSharedPreferences("compression_state", MODE_PRIVATE)
                val names = prefs.getStringSet("restored_original_names", emptySet())!!.toMutableSet()
                names.add(fileName)
                prefs.edit().putStringSet("restored_original_names", names).apply()
                // Remove from make_space processed so it can be re-queued later
                val spacePrefs = getSharedPreferences("make_space_state", MODE_PRIVATE)
                val processed = spacePrefs.getStringSet("processed_names", emptySet())!!.toMutableSet()
                processed.remove(fileName)
                spacePrefs.edit().putStringSet("processed_names", processed).apply()
            }

            runOnUiThread {
                Toast.makeText(this,
                    if (restored) "Original restored: $fileName" else "Failed to restore file",
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun findOnHub(ip: String, port: Int, fileName: String) =
        try { HubFilesClient.fetchFiles(ip, port, limit = 10_000).firstOrNull { it.displayName == fileName } }
        catch (_: Exception) { null }

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
}
