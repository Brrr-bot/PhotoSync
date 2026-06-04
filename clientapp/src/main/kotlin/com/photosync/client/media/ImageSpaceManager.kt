package com.photosync.client.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import com.photosync.client.util.RemoteLogger

/**
 * Compresses phone images to WebP after the hub confirms they are backed up on USB.
 *
 * This is the single compression step for images. The hub backs up originals to USB
 * (no compression there), then this class converts the phone's copy to WebP at 92% quality
 * with all EXIF preserved. Because this runs on the phone (Android 13), ExifInterface can
 * write EXIF to WebP — GPS, camera, dates, orientation all survive.
 *
 * Tracks compressed names in SharedPrefs so each image is only processed once.
 * Bump COMPRESS_VERSION to force a full re-run (e.g. after changing quality settings).
 */
class ImageSpaceManager(private val context: Context) {

    data class Summary(val compressed: Int, val skipped: Int, val freedBytes: Long)

    private val prefs = context.getSharedPreferences("image_space_state", Context.MODE_PRIVATE)

    fun process(progress: ((done: Int, total: Int, name: String) -> Unit)? = null): Summary {
        val ip = ClientForegroundService.liveHubIp
            ?.takeIf { System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L }
            ?: ClientForegroundService.liveHubTailscaleIp
            ?: return Summary(0, 0, 0)
        val port = ClientForegroundService.liveHubPort

        val hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) }
            catch (_: Exception) { return Summary(0, 0, 0) }
        if (hubFiles.isEmpty()) return Summary(0, 0, 0)

        // Set of display names confirmed backed up on hub USB
        val hubNames = hubFiles.map { it.displayName }.toHashSet()

        val compressedNames = prefs.getStringSet(KEY_COMPRESSED, emptySet())!!.toMutableSet()

        // All phone images not yet converted to WebP and not already compressed
        val phoneImages = MediaStoreHelper(context).getMediaSince(0).filter { f ->
            f.mimeType.startsWith("image/") &&
            f.mimeType != "image/webp" &&
            f.displayName in hubNames &&
            f.displayName !in compressedNames
        }

        var compressed = 0
        var skipped = 0
        var freedBytes = 0L
        val total = phoneImages.size

        for ((index, image) in phoneImages.withIndex()) {
            progress?.invoke(index + 1, total, image.displayName)
            try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image.id)
                val originalBytes = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() } ?: run { skipped++; continue }

                val webpBytes = WebPConverter.convert(originalBytes, context.cacheDir)
                if (webpBytes == null || webpBytes.size >= originalBytes.size) {
                    // Can't convert (old Android) or no size benefit — mark done, skip
                    compressedNames.add(image.displayName)
                    skipped++
                    continue
                }

                val store = MediaStoreHelper(context)
                store.replaceFile(image.id, "image/webp", webpBytes, image.dateTaken)
                freedBytes += (originalBytes.size - webpBytes.size).toLong()
                compressedNames.add(image.displayName)
                compressed++
            } catch (t: Throwable) {
                RemoteLogger.i("ImageSpace error ${image.displayName}: ${t.javaClass.simpleName}: ${t.message}")
                skipped++
            }
        }

        prefs.edit().putStringSet(KEY_COMPRESSED, compressedNames).apply()
        return Summary(compressed, skipped, freedBytes)
    }

    companion object {
        private const val KEY_COMPRESSED = "compressed_image_names"
        // Bump to force a full re-run on all devices
        const val COMPRESS_VERSION = 1
        const val KEY_COMPRESS_VERSION = "image_compress_version"
    }
}
