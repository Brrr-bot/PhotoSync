package com.photosync.client.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Re-encodes a source image as WebP, copying all EXIF from the source.
 *
 * Quality 72 targets roughly the same perceptual level as JPEG 75 (what most apps such as
 * Zalo/WhatsApp use for social sharing). At this setting a typical 3–4 MB Samsung JPEG
 * compresses to ~500–700 KB — comparable to social-app output.
 *
 * Callers should only keep the result when webpBytes.size < sourceBytes.size.
 *
 * Requires API 31+ for ExifInterface WebP write support. Returns null on older devices
 * so the caller falls back to whatever bytes it already has.
 */
object WebPConverter {

    private const val QUALITY = 72

    fun convert(sourceBytes: ByteArray, cacheDir: File): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                ?: return null
            val w = bitmap.width
            val h = bitmap.height

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            bitmap.recycle()
            val simpleWebp = out.toByteArray()

            // PREFERRED — lossless metadata copy: lift the raw EXIF (incl. vendor MakerNotes), ICC
            // colour profile and XMP byte-blocks straight out of the source JPEG and mux them into
            // the WebP as native EXIF/ICCP/XMP chunks. Nothing is interpreted or dropped. We only
            // use the result if it decodes cleanly, so a malformed mux can never reach storage.
            val muxed = try { rawCopyMetadata(sourceBytes, simpleWebp, w, h) } catch (_: Throwable) { null }
            if (muxed != null && isDecodable(muxed)) return muxed

            // FALLBACK — tag-by-tag copy via ExifInterface (used for non-JPEG sources, or if the
            // raw mux failed validation). Carries the curated EXIF/XMP set verbatim.
            convertWithExifInterface(sourceBytes, simpleWebp, cacheDir)
        } catch (_: Exception) { null }
    }

    /** True if [bytes] is a structurally valid, decodable image (header-only decode — cheap). */
    private fun isDecodable(bytes: ByteArray): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    } catch (_: Throwable) { false }

    private fun convertWithExifInterface(sourceBytes: ByteArray, simpleWebp: ByteArray, cacheDir: File): ByteArray? {
        val srcExif = ExifInterface(ByteArrayInputStream(sourceBytes))
        val tmp = File.createTempFile("ps_webp_", ".webp", cacheDir)
        return try {
            tmp.writeBytes(simpleWebp)
            ExifInterface(tmp.absolutePath).also { dst ->
                // Copy EVERY tag — including TAG_ORIENTATION — verbatim from the source.
                // BitmapFactory.decodeByteArray does NOT apply EXIF rotation, so the WebP must
                // carry the SAME orientation tag as the original or portraits display sideways.
                for (tag in TAGS) {
                    srcExif.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                }
                dst.saveAttributes()
            }
            tmp.readBytes()
        } catch (_: Exception) { null } finally { tmp.delete() }
    }

    // ── Raw metadata mux (JPEG segments → WebP chunks) ─────────────────────────

    private class JpegMeta(val exifTiff: ByteArray?, val icc: ByteArray?, val xmp: ByteArray?)

    /** Builds a VP8X WebP from [simpleWebp]'s image data plus the raw EXIF/ICC/XMP lifted from the
     *  source JPEG. Returns null if the source has no metadata or isn't a parseable JPEG/WebP. */
    private fun rawCopyMetadata(jpeg: ByteArray, simpleWebp: ByteArray, w: Int, h: Int): ByteArray? {
        val meta = extractJpegMeta(jpeg)
        if (meta.exifTiff == null && meta.icc == null && meta.xmp == null) return null
        // simpleWebp must be a plain "RIFF....WEBP<imagechunk>" produced by Bitmap.compress.
        if (simpleWebp.size < 16) return null
        if (!regionEquals(simpleWebp, 0, "RIFF".toByteArray(Charsets.ISO_8859_1)) ||
            !regionEquals(simpleWebp, 8, "WEBP".toByteArray(Charsets.ISO_8859_1))) return null
        val imageChunk = simpleWebp.copyOfRange(12, simpleWebp.size)   // the VP8 / VP8L chunk, verbatim

        val body = ByteArrayOutputStream()
        var flags = 0
        if (meta.icc != null)      flags = flags or 0x20
        if (meta.exifTiff != null) flags = flags or 0x08
        if (meta.xmp != null)      flags = flags or 0x04
        val vp8x = ByteArray(10)
        vp8x[0] = flags.toByte()
        val cw = w - 1; val ch = h - 1
        vp8x[4] = (cw and 0xFF).toByte(); vp8x[5] = ((cw shr 8) and 0xFF).toByte(); vp8x[6] = ((cw shr 16) and 0xFF).toByte()
        vp8x[7] = (ch and 0xFF).toByte(); vp8x[8] = ((ch shr 8) and 0xFF).toByte(); vp8x[9] = ((ch shr 16) and 0xFF).toByte()
        writeChunk(body, "VP8X", vp8x)
        meta.icc?.let { writeChunk(body, "ICCP", it) }    // ICC must precede the image
        body.write(imageChunk)
        meta.exifTiff?.let { writeChunk(body, "EXIF", it) }
        meta.xmp?.let { writeChunk(body, "XMP ", it) }
        val bodyBytes = body.toByteArray()

        val riff = ByteArrayOutputStream()
        riff.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        riff.write(intLE(4 + bodyBytes.size))   // "WEBP" + body
        riff.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        riff.write(bodyBytes)
        return riff.toByteArray()
    }

    /** Pulls the raw EXIF TIFF block, full ICC profile and XMP packet out of a JPEG's APP segments. */
    private fun extractJpegMeta(jpeg: ByteArray): JpegMeta {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8)
            return JpegMeta(null, null, null)
        val exifPrefix = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)              // 6 bytes
        val xmpPrefix  = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val iccPrefix  = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)             // 12 bytes
        var exif: ByteArray? = null
        var xmp: ByteArray? = null
        val iccParts = ArrayList<ByteArray>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != 0xFF) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break              // EOI / start-of-scan → no more metadata
            if (marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }   // standalone markers, no length
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpeg.size) break
            val segStart = pos + 4
            val segLen = len - 2
            when (marker) {
                0xE1 -> when {
                    segLen >= exifPrefix.size && regionEquals(jpeg, segStart, exifPrefix) ->
                        if (exif == null) exif = jpeg.copyOfRange(segStart + exifPrefix.size, segStart + segLen)
                    segLen >= xmpPrefix.size && regionEquals(jpeg, segStart, xmpPrefix) ->
                        if (xmp == null) xmp = jpeg.copyOfRange(segStart + xmpPrefix.size, segStart + segLen)
                }
                0xE2 -> if (segLen >= iccPrefix.size + 2 && regionEquals(jpeg, segStart, iccPrefix)) {
                    // 12-byte prefix + 1 seq-no + 1 count, then the ICC chunk payload
                    iccParts.add(jpeg.copyOfRange(segStart + iccPrefix.size + 2, segStart + segLen))
                }
            }
            pos += 2 + len
        }
        val icc = if (iccParts.isEmpty()) null else iccParts.reduce { a, b -> a + b }
        return JpegMeta(exif, icc, xmp)
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCC: String, data: ByteArray) {
        out.write(fourCC.toByteArray(Charsets.ISO_8859_1))
        out.write(intLE(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)   // RIFF chunks are padded to an even size
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun regionEquals(buf: ByteArray, off: Int, prefix: ByteArray): Boolean {
        if (off < 0 || off + prefix.size > buf.size) return false
        for (i in prefix.indices) if (buf[off + i] != prefix[i]) return false
        return true
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
        // Focal plane
        ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
        // Zoom / digital processing
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SPECTRAL_SENSITIVITY,
        ExifInterface.TAG_INTEROPERABILITY_INDEX,
        // XMP — full XML metadata packet (keywords, ratings, editing app data, etc.)
        ExifInterface.TAG_XMP,
        // Misc
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
    )
}
