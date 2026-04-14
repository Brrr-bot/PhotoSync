package com.photosync.hub.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaCompressor {

    private const val MAX_DIMENSION = 1920
    private const val JPEG_QUALITY = 82

    /**
     * Compresses an image from [originalBytes] to JPEG at [JPEG_QUALITY]% quality,
     * scaled so the longest side is at most [MAX_DIMENSION]px.
     * Preserves EXIF orientation by baking the rotation into the pixel data.
     * Returns null if the input is not a recognised image or is already smaller than the result.
     */
    /**
     * Compresses [originalBytes] and stamps [dateTakenMs] into the output JPEG EXIF so
     * the phone's MediaStore scanner sets DATE_TAKEN correctly without any client-side
     * ExifInterface calls (which risk native JNI crashes on certain Android versions).
     *
     * @param cacheDir  Writable directory for the temp file used during EXIF stamping.
     *                  Pass [android.content.Context.getCacheDir].  If null, EXIF stamping
     *                  is skipped (ContentValues DATE_TAKEN is the fallback).
     * @param dateTakenMs  Original capture time in milliseconds, or 0 to skip stamping.
     */
    fun compressImage(originalBytes: ByteArray, dateTakenMs: Long = 0L, cacheDir: File? = null): ByteArray? {
        return try {
            // Read EXIF orientation BEFORE decoding — must use a fresh stream
            val orientation = try {
                ExifInterface(ByteArrayInputStream(originalBytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // Decode only bounds first to check dimensions cheaply
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, opts)

            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null   // not an image

            // Calculate inSampleSize for fast decode at roughly target size
            val inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, MAX_DIMENSION)
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOpts)
                ?: return null

            // Bake EXIF rotation into pixels so galleries that ignore EXIF show correct orientation
            val oriented = applyOrientation(sampled, orientation)

            // Scale precisely to MAX_DIMENSION on longest side
            val scaled = scaleTo(oriented, MAX_DIMENSION)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

            // Recycle intermediate bitmaps
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== sampled) oriented.recycle()
            sampled.recycle()

            val compressed = out.toByteArray()
            if (compressed.size >= originalBytes.size) return null   // already optimal

            // Stamp DateTimeOriginal into the compressed JPEG via a temp file so the
            // phone's MediaStore scanner reads the correct capture date from EXIF.
            // This avoids all ExifInterface use on the client side (JNI crash risk).
            if (dateTakenMs > 0L && cacheDir != null) {
                stampExifDate(compressed, dateTakenMs, cacheDir)?.let { return it }
            }
            compressed
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Writes [dateTakenMs] as EXIF [DateTimeOriginal]/[DateTime] into a copy of [jpegBytes].
     * Uses a temp file in [cacheDir] because ExifInterface requires a seekable file.
     * Returns the modified bytes, or null if stamping fails (caller falls back to plain bytes).
     */
    private fun stampExifDate(jpegBytes: ByteArray, dateTakenMs: Long, cacheDir: File): ByteArray? {
        var tmp: File? = null
        return try {
            tmp = File.createTempFile("ps_exif_", ".jpg", cacheDir)
            tmp.writeBytes(jpegBytes)
            val formatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(dateTakenMs))
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
                setAttribute(ExifInterface.TAG_DATETIME,          formatted)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
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

    /**
     * Rotates/flips [bitmap] to match the EXIF [orientation] tag, baking the
     * transform into the pixel data so the output JPEG needs no orientation tag.
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90    -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180   -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270   -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE    -> { matrix.postScale(-1f, 1f); matrix.postRotate(-90f) }
            ExifInterface.ORIENTATION_TRANSVERSE   -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return bitmap   // ORIENTATION_NORMAL or UNDEFINED — nothing to do
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return rotated
    }

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
