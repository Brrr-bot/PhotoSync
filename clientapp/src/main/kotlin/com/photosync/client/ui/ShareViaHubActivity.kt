package com.photosync.client.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import java.io.File

/**
 * Invisible trampoline activity registered as an ACTION_SEND share target.
 * Receives an image/video from any app (e.g. Samsung Gallery), matches the
 * filename to the hub, downloads the original, and re-opens the system share
 * sheet with the full-resolution file.
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

        val ip   = ClientForegroundService.liveHubTailscaleIp
                   ?: ClientForegroundService.liveHubIp
        val port = ClientForegroundService.liveHubPort

        if (ip == null) {
            Toast.makeText(this, "Hub not reachable — connect to hub first", Toast.LENGTH_LONG).show()
            finish(); return
        }

        Toast.makeText(this, "Fetching original from hub…", Toast.LENGTH_SHORT).show()

        Thread {
            // Find the file on the hub by display name
            val files = HubFilesClient.fetchFiles(ip, port, limit = 10_000)
            val match = files.firstOrNull { it.displayName == fileName }

            if (match == null) {
                runOnUiThread {
                    Toast.makeText(this, "\"$fileName\" not found on hub", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@Thread
            }

            val bytes = HubFilesClient.fetchFile(ip, port, match.deviceName, match.displayName)
            if (bytes == null) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@Thread
            }

            val mime = when (match.displayName.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png"         -> "image/png"
                "webp"        -> "image/webp"
                "mp4"         -> "video/mp4"
                "mov"         -> "video/quicktime"
                else          -> "image/jpeg"
            }

            val tmpFile = File(cacheDir, match.displayName)
            tmpFile.writeBytes(bytes)
            val shareUri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)

            runOnUiThread {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share ${match.displayName}"))
                finish()
            }
        }.start()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        // Try ContentResolver first (works for content:// URIs from Gallery)
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) return cursor.getString(col)
                }
            }
        // Fall back to last path segment
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
    }
}
