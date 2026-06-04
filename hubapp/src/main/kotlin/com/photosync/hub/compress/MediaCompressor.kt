package com.photosync.hub.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaCompressor {

    private const val MAX_DIMENSION = 1920
    private const val WEBP_QUALITY  = 85

    /** MIME type of the compressed output — callers must send this to /replace. */
    const val OUTPUT_MIME_TYPE = "image/webp"

    /**
     * Compresses [originalBytes] to JPEG at [JPEG_QUALITY]%, scaled so the longest side is
     * at most [MAX_DIMENSION]px. Preserves EXIF orientation tag (does NOT bake into pixels —
     * baking caused double-rotation on Samsung devices whose camera sets ROTATE_90 in EXIF
     * while pixels are already correctly oriented). Returns null if the input is not a
     * recognised image or the compressed result would be larger than the original.
     *
     * @param cacheDir     Writable dir for the temp file used during EXIF stamping. Pass
     *                     [android.content.Context.getCacheDir]. Null skips EXIF stamping.
     * @param dateTakenMs  Capture time in ms, or 0 to skip date stamping.
     */
    fun compressImage(originalBytes: ByteArray, dateTakenMs: Long = 0L, cacheDir: File? = null): ByteArray? {
        return try {
            // Read EXIF orientation BEFORE decoding — must use a fresh stream.
            // We preserve it in the output EXIF rather than baking it into pixels;
            // baking caused incorrect rotation on Samsung devices where the camera
            // sets ROTATE_90 in EXIF but pixels are already correctly oriented.
            val orientation = try {
                ExifInterface(ByteArrayInputStream(originalBytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // Decode only bounds first to check dimensions cheaply
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, opts)

            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w("MediaCompressor", "decode failed: bounds (${opts.outWidth}x${opts.outHeight}) for ${originalBytes.size}B input")
                return null
            }

            // Calculate inSampleSize for fast decode at roughly target size
            val inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, MAX_DIMENSION)
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOpts)
                ?: return null

            // Scale precisely to MAX_DIMENSION on longest side (no pixel rotation — orientation
            // is preserved via EXIF tag so all modern galleries display it correctly)
            val scaled = scaleTo(sampled, MAX_DIMENSION)

            val out = ByteArrayOutputStream()
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            scaled.compress(fmt, WEBP_QUALITY, out)

            // Recycle intermediate bitmaps
            if (scaled !== sampled) scaled.recycle()
            sampled.recycle()

            val compressed = out.toByteArray()
            if (compressed.size >= originalBytes.size) return null   // already optimal

            // Stamp DateTimeOriginal + preserve orientation tag into the compressed JPEG.
            if (cacheDir != null && (dateTakenMs > 0L || orientation != ExifInterface.ORIENTATION_NORMAL)) {
                stampExifDate(compressed, dateTakenMs, orientation, cacheDir)?.let { return it }
            }
            compressed
        } catch (e: Exception) {
            Log.w("MediaCompressor", "compress threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Writes [dateTakenMs] as EXIF [DateTimeOriginal]/[DateTime] into a copy of [jpegBytes].
     * Uses a temp file in [cacheDir] because ExifInterface requires a seekable file.
     * Returns the modified bytes, or null if stamping fails (caller falls back to plain bytes).
     */
    private fun stampExifDate(
        jpegBytes: ByteArray,
        dateTakenMs: Long,
        orientation: Int,
        cacheDir: File
    ): ByteArray? {
        var tmp: File? = null
        return try {
            // ExifInterface can write to WebP on API 31+; skip EXIF stamp on older.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            tmp = File.createTempFile("ps_exif_", ".webp", cacheDir)
            tmp.writeBytes(jpegBytes)
            ExifInterface(tmp.absolutePath).apply {
                if (dateTakenMs > 0L) {
                    val formatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(dateTakenMs))
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
                    setAttribute(ExifInterface.TAG_DATETIME,          formatted)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
                }
                // Preserve original orientation so galleries display correctly without
                // baking rotation into pixels (which corrupted Samsung portrait photos).
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            tmp.readBytes()
        } catch (_: Exception) {
            null   // non-fatal: client ContentValues DATE_TAKEN is the fallback
        } finally {
            tmp?.delete()
        }
    }

    /** Returns true for mime types we can compress. */
    fun canCompress(mimeType: String) = mimeType.startsWith("image/") &&
            mimeType != "image/gif" && mimeType != "image/svg+xml"

    private fun calcSampleSize(width: Int, height: Int, target: Int): Int {
        var size = 1
        var w = width; var h = height
        while (w / 2 >= target && h / 2 >= target) { w /= 2; h /= 2; size *= 2 }
        return size
    }

    private fun scaleTo(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        return if (w >= h) {
            val newH = (h.toFloat() / w * maxDim).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, maxDim, newH, true)
        } else {
            val newW = (w.toFloat() / h * maxDim).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, newW, maxDim, true)
        }
    }
}
