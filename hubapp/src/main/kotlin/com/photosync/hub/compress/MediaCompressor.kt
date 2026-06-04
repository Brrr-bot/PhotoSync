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
     * Compresses [originalBytes] to WebP at [WEBP_QUALITY]%, scaled so the longest side is
     * at most [MAX_DIMENSION]px. Copies ALL EXIF metadata from the original into the output
     * (GPS, camera model, ISO, focal length, orientation, dates, etc.).
     * Returns null if the input is not a recognised image or the output would be larger.
     *
     * @param cacheDir     Writable dir for the temp file used during EXIF stamping.
     *                     Null skips EXIF stamping (dates still set via ContentValues on client).
     * @param dateTakenMs  Capture time in ms, or 0 to use the date found in EXIF.
     */
    fun compressImage(originalBytes: ByteArray, dateTakenMs: Long = 0L, cacheDir: File? = null): ByteArray? {
        return try {
            // Decode only bounds first to check dimensions cheaply
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, opts)

            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w("MediaCompressor", "decode failed for ${originalBytes.size}B input")
                return null
            }

            val inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, MAX_DIMENSION)
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOpts)
                ?: return null

            val scaled = scaleTo(sampled, MAX_DIMENSION)

            val out = ByteArrayOutputStream()
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            scaled.compress(fmt, WEBP_QUALITY, out)

            if (scaled !== sampled) scaled.recycle()
            sampled.recycle()

            val compressed = out.toByteArray()
            if (compressed.size >= originalBytes.size) return null

            // Copy ALL EXIF from original into the WebP output.
            // ExifInterface can write to WebP on API 31+; on older we return without EXIF
            // (the client still sets DATE_TAKEN via ContentValues so gallery ordering is correct).
            if (cacheDir != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                copyExif(originalBytes, compressed, dateTakenMs, cacheDir)?.let { return it }
            }
            compressed
        } catch (e: Exception) {
            Log.w("MediaCompressor", "compress threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Copies every readable EXIF tag from [originalBytes] into [compressedBytes], then
     * overrides the date fields with [dateTakenMs] if supplied.
     * Uses a temp file because ExifInterface requires a seekable file.
     */
    private fun copyExif(
        originalBytes: ByteArray,
        compressedBytes: ByteArray,
        dateTakenMs: Long,
        cacheDir: File
    ): ByteArray? {
        var tmp: File? = null
        return try {
            val srcExif = ExifInterface(ByteArrayInputStream(originalBytes))

            tmp = File.createTempFile("ps_exif_", ".webp", cacheDir)
            tmp.writeBytes(compressedBytes)

            ExifInterface(tmp.absolutePath).apply {
                // Copy every preservable tag from the original
                for (tag in ALL_EXIF_TAGS) {
                    srcExif.getAttribute(tag)?.let { setAttribute(tag, it) }
                }

                // Override date fields: prefer the hub-supplied dateTakenMs (from USB EXIF),
                // then fall back to whatever the original EXIF already had.
                val dateStr = if (dateTakenMs > 0L) {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(dateTakenMs))
                } else {
                    srcExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                }
                if (dateStr != null) {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                    setAttribute(ExifInterface.TAG_DATETIME,           dateStr)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED,  dateStr)
                }

                saveAttributes()
            }
            tmp.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            tmp?.delete()
        }
    }

    /** Returns true for MIME types we can decode and re-compress. */
    fun canCompress(mimeType: String) = mimeType.startsWith("image/") &&
            mimeType != "image/gif" && mimeType != "image/svg+xml"

    // ── All EXIF tags we want to preserve ────────────────────────────────────

    private val ALL_EXIF_TAGS = arrayOf(
        // ── Dates & times ──
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // ── Camera / device ──
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        // ── Lens ──
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        // ── Exposure ──
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        // ── Image geometry ──
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        // ── GPS ──
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_VERSION_ID,
        // ── Misc ──
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
    )

    // ── Bitmap helpers ────────────────────────────────────────────────────────

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
