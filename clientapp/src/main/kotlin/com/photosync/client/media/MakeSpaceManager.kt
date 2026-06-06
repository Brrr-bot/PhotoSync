package com.photosync.client.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.MediaStore
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import com.photosync.client.util.RemoteLogger
import java.io.ByteArrayOutputStream

/**
 * Make Space — frees phone storage for files already backed up on the hub.
 *
 *  Files confirmed on hub:
 *   < 1 month old  ->  compress to WebP 85 %  (decent quality, much smaller)
 *   >= 1 month old ->  replace with a small placeholder JPEG: blurred thumbnail +
 *                      dark overlay + cloud glyph + "Saved on Hub" text
 *
 * Skips:
 *  - Files in restored_original_names  (user just downloaded them back from hub)
 *  - Files already handled in a previous Make Space run (make_space_state/processed_names)
 */
class MakeSpaceManager(private val context: Context) {

    data class Summary(
        val compressed: Int,
        val posterized: Int,
        val skipped: Int,
        val freedBytes: Long
    )

    private val compressionPrefs = context.getSharedPreferences("compression_state", Context.MODE_PRIVATE)
    private val spacePrefs       = context.getSharedPreferences("make_space_state",  Context.MODE_PRIVATE)

    companion object {
        // Bump this when compression quality/algorithm changes so all photos are re-processed.
        // v1 = WebP quality 92 (too large)
        // v2 = WebP quality 72 (matches social-app ratio ~500KB per 3MB photo)
        private const val COMPRESS_VERSION     = 2
        private const val KEY_COMPRESS_VERSION = "compress_version"
    }

    fun process(progress: ((done: Int, total: Int, msg: String) -> Unit)? = null): Summary {
        val ip = effectiveHubIp() ?: return Summary(0, 0, 0, 0)
        val port = ClientForegroundService.liveHubPort

        val hubFiles = try {
            HubFilesClient.fetchFiles(ip, port, limit = 10_000)
        } catch (_: Exception) { return Summary(0, 0, 0, 0) }
        if (hubFiles.isEmpty()) return Summary(0, 0, 0, 0)

        val hubNames       = hubFiles.map { it.displayName }.toHashSet()
        val restoredNames  = compressionPrefs.getStringSet("restored_original_names", emptySet())!!

        // Clear processed set when quality version has been bumped so all photos re-compress
        val storedVersion = spacePrefs.getInt(KEY_COMPRESS_VERSION, 0)
        if (storedVersion < COMPRESS_VERSION) {
            spacePrefs.edit()
                .remove("processed_names")
                .putInt(KEY_COMPRESS_VERSION, COMPRESS_VERSION)
                .apply()
        }
        val processedNames = spacePrefs.getStringSet("processed_names", emptySet())!!.toMutableSet()

        val mediaStore       = MediaStoreHelper(context)
        val oneMonthAgoSec   = System.currentTimeMillis() / 1000L - 30L * 24 * 3600

        val phoneImages = mediaStore.getMediaSince(0).filter { f ->
            f.mimeType.startsWith("image/") &&
            f.displayName in hubNames &&
            f.displayName !in restoredNames &&
            f.displayName !in processedNames
        }

        var compressed = 0
        var posterized = 0
        var skipped    = 0
        var freedBytes = 0L
        val total = phoneImages.size

        // Build a map from displayName -> hub entry so we can download originals
        val hubByName = hubFiles.associateBy { it.displayName }

        for ((index, image) in phoneImages.withIndex()) {
            progress?.invoke(index + 1, total, image.displayName)
            try {
                // Always compress from the hub original so re-runs with new settings
                // produce the best possible quality regardless of what's on the phone.
                val hubEntry = hubByName[image.displayName]
                val originalBytes: ByteArray
                if (hubEntry != null) {
                    val tmp = java.io.File(context.cacheDir, "ms_orig_${image.id}")
                    val ok = HubFilesClient.fetchFileToFile(ip, port, hubEntry.deviceName, hubEntry.displayName, tmp)
                    if (!ok || tmp.length() == 0L) { tmp.delete(); skipped++; continue }
                    originalBytes = tmp.readBytes()
                    tmp.delete()
                } else {
                    // Hub entry not found — fall back to phone's local copy
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image.id)
                    originalBytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: run { skipped++; continue }
                }

                val isOld = image.dateAdded < oneMonthAgoSec

                if (isOld) {
                    val posterBytes = createPosterImage(originalBytes) ?: run { skipped++; continue }
                    mediaStore.replaceFile(image.id, "image/jpeg", posterBytes, image.dateTaken)
                    freedBytes += (originalBytes.size - posterBytes.size).coerceAtLeast(0).toLong()
                    posterized++
                } else {
                    val webpBytes = WebPConverter.convert(originalBytes, context.cacheDir)
                    if (webpBytes == null) {
                        processedNames.add(image.displayName); skipped++; continue
                    }
                    mediaStore.replaceFile(image.id, "image/webp", webpBytes, image.dateTaken)
                    freedBytes += (originalBytes.size - webpBytes.size).coerceAtLeast(0).toLong()
                    compressed++
                }
                processedNames.add(image.displayName)
            } catch (t: Throwable) {
                RemoteLogger.i("MakeSpace error ${image.displayName}: ${t.javaClass.simpleName}: ${t.message}")
                skipped++
            }
        }

        spacePrefs.edit().putStringSet("processed_names", processedNames).apply()
        return Summary(compressed, posterized, skipped, freedBytes)
    }

    /**
     * Creates a small JPEG placeholder:
     *  - thumbnail at max 480px longest side
     *  - dark translucent overlay
     *  - cloud glyph + "Saved on Hub" label
     */
    private fun createPosterImage(originalBytes: ByteArray): ByteArray? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, opts)

            val maxPx = 480
            val longestSide = maxOf(opts.outWidth, opts.outHeight)
            val sampleSize  = if (longestSide > maxPx) longestSide / maxPx else 1

            val decOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decOpts)
                ?: return null

            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()

            val canvas = Canvas(mutable)
            val w = mutable.width.toFloat()
            val h = mutable.height.toFloat()

            // Dark overlay
            canvas.drawRect(0f, 0f, w, h, Paint().apply { color = 0xCC000000.toInt() })

            // Cloud glyph
            val cloudPaint = Paint().apply {
                color = 0xFFFFFFFF.toInt()
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = h * 0.22f
            }
            canvas.drawText("☁", w / 2f, h * 0.52f, cloudPaint)

            // Label
            val labelPaint = Paint().apply {
                color = 0xCCFFFFFF.toInt()
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = h * 0.075f
            }
            canvas.drawText("Saved on Hub", w / 2f, h * 0.70f, labelPaint)

            val baos = ByteArrayOutputStream()
            mutable.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            mutable.recycle()
            baos.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun effectiveHubIp(): String? {
        val localIp = ClientForegroundService.liveHubIp
        val tsIp    = ClientForegroundService.liveHubTailscaleIp
        val fresh   = System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L
        return when {
            localIp != null && fresh -> localIp
            tsIp    != null          -> tsIp
            else                     -> localIp
        }
    }
}
