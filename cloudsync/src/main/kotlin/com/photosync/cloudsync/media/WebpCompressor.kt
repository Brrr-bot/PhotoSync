package com.photosync.cloudsync.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.photosync.cloudsync.util.RemoteLogger
import java.io.ByteArrayOutputStream

/**
 * Compresses an image to WebP, copying the source JPEG's EXIF (incl. orientation, capture date,
 * GPS) verbatim via a raw chunk mux. The raw mux uses no ExifInterface write, so it works on the
 * tablet's older Android too. Non-JPEG sources fall back to a plain (metadata-less) WebP.
 */
object WebpCompressor {

    fun compress(sourceBytes: ByteArray, quality: Int): ByteArray? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size) ?: return null
            val w = bmp.width; val h = bmp.height
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                      else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, quality, out)
            bmp.recycle()
            val simple = out.toByteArray()
            val muxed = try { rawCopyMetadata(sourceBytes, simple, w, h) } catch (_: Throwable) { null }
            if (muxed != null && isDecodable(muxed)) muxed else simple
        } catch (t: Throwable) { RemoteLogger.e("webp compress failed", t); null }
    }

    private fun isDecodable(bytes: ByteArray): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        o.outWidth > 0 && o.outHeight > 0
    } catch (_: Throwable) { false }

    private class JpegMeta(val exifTiff: ByteArray?, val icc: ByteArray?, val xmp: ByteArray?)

    private fun rawCopyMetadata(jpeg: ByteArray, simpleWebp: ByteArray, w: Int, h: Int): ByteArray? {
        val meta = extractJpegMeta(jpeg)
        if (meta.exifTiff == null && meta.icc == null && meta.xmp == null) return null
        if (simpleWebp.size < 16) return null
        if (!regionEquals(simpleWebp, 0, "RIFF".toByteArray(Charsets.ISO_8859_1)) ||
            !regionEquals(simpleWebp, 8, "WEBP".toByteArray(Charsets.ISO_8859_1))) return null
        var hasAlpha = false
        val imageBody = ByteArrayOutputStream()
        var p = 12
        while (p + 8 <= simpleWebp.size) {
            val cc = String(simpleWebp, p, 4, Charsets.ISO_8859_1)
            val sz = intFromLE(simpleWebp, p + 4)
            val dataStart = p + 8
            if (sz < 0 || dataStart + sz > simpleWebp.size) break
            val padded = sz + (sz and 1)
            when (cc) {
                "VP8X" -> {}
                "ALPH" -> { hasAlpha = true; imageBody.write(simpleWebp, p, 8 + padded) }
                "VP8 ", "VP8L", "ANIM", "ANMF" -> imageBody.write(simpleWebp, p, 8 + padded)
                else -> {}
            }
            p = dataStart + padded
        }
        val imageChunks = imageBody.toByteArray()
        if (imageChunks.isEmpty()) return null

        val body = ByteArrayOutputStream()
        var flags = 0
        if (meta.icc != null) flags = flags or 0x20
        if (hasAlpha) flags = flags or 0x10
        if (meta.exifTiff != null) flags = flags or 0x08
        if (meta.xmp != null) flags = flags or 0x04
        val vp8x = ByteArray(10)
        vp8x[0] = flags.toByte()
        val cw = w - 1; val ch = h - 1
        vp8x[4] = (cw and 0xFF).toByte(); vp8x[5] = ((cw shr 8) and 0xFF).toByte(); vp8x[6] = ((cw shr 16) and 0xFF).toByte()
        vp8x[7] = (ch and 0xFF).toByte(); vp8x[8] = ((ch shr 8) and 0xFF).toByte(); vp8x[9] = ((ch shr 16) and 0xFF).toByte()
        writeChunk(body, "VP8X", vp8x)
        meta.icc?.let { writeChunk(body, "ICCP", it) }
        body.write(imageChunks)
        meta.exifTiff?.let { writeChunk(body, "EXIF", it) }
        meta.xmp?.let { writeChunk(body, "XMP ", it) }
        val bodyBytes = body.toByteArray()

        val riff = ByteArrayOutputStream()
        riff.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        riff.write(intLE(4 + bodyBytes.size))
        riff.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        riff.write(bodyBytes)
        return riff.toByteArray()
    }

    private fun extractJpegMeta(jpeg: ByteArray): JpegMeta {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8)
            return JpegMeta(null, null, null)
        val exifPrefix = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val xmpPrefix  = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val iccPrefix  = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
        var exif: ByteArray? = null
        var xmp: ByteArray? = null
        val iccParts = ArrayList<ByteArray>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != 0xFF) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
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
                0xE2 -> if (segLen >= iccPrefix.size + 2 && regionEquals(jpeg, segStart, iccPrefix))
                    iccParts.add(jpeg.copyOfRange(segStart + iccPrefix.size + 2, segStart + segLen))
            }
            pos += 2 + len
        }
        val icc = if (iccParts.isEmpty()) null else iccParts.reduce { a, b -> a + b }
        return JpegMeta(exif, icc, xmp)
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCC: String, data: ByteArray) {
        out.write(fourCC.toByteArray(Charsets.ISO_8859_1)); out.write(intLE(data.size)); out.write(data)
        if (data.size % 2 == 1) out.write(0)
    }
    private fun intLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun intFromLE(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or
        ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    private fun regionEquals(b: ByteArray, off: Int, prefix: ByteArray): Boolean {
        if (off < 0 || off + prefix.size > b.size) return false
        for (i in prefix.indices) if (b[off + i] != prefix[i]) return false
        return true
    }
}
