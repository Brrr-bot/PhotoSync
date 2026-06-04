package com.photosync.client.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Re-encodes a hub-compressed JPEG as WebP, copying all EXIF from the source.
 *
 * The hub already scaled the image to ≤1920px and compressed it with full EXIF, so this is
 * purely a format conversion — no further scaling or quality loss beyond the codec difference.
 * WebP at 92% gives visibly better quality than JPEG at 85% at a similar or smaller file size.
 *
 * Requires API 31+ for ExifInterface WebP write support. Returns null on older devices
 * so the caller falls back to the JPEG bytes the hub sent.
 */
object WebPConverter {

    private const val QUALITY = 92

    fun convert(sourceBytes: ByteArray, cacheDir: File): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val srcExif = ExifInterface(ByteArrayInputStream(sourceBytes))

            val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                ?: return null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            bitmap.recycle()
            val webpBytes = out.toByteArray()

            // Copy all EXIF tags from the source into the WebP
            val tmp = File.createTempFile("ps_webp_", ".webp", cacheDir)
            try {
                tmp.writeBytes(webpBytes)
                ExifInterface(tmp.absolutePath).also { dst ->
                    for (tag in TAGS) {
                        srcExif.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                    }
                    dst.saveAttributes()
                }
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) { null }
    }

    private val TAGS = arrayOf(
        // Dates
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // Camera / device
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        // Lens
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        // Exposure
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_SENSITIVITY_TYPE,
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
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        // Image geometry — preserve orientation so viewers display correctly
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_COLOR_SPACE,
        // GPS
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
        // Misc
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
    )
}
