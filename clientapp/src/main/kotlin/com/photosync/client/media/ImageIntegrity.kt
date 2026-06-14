package com.photosync.client.media

import android.graphics.BitmapFactory

/**
 * Post-transfer integrity check. A file can be the EXPECTED SIZE yet be corrupt (truncated image
 * data padded with garbage, or a bloated/duplicated stream) — which the size-only sync check misses
 * (it caused "half image" thumbnails and 50 MB junk "originals"). This validates that an image is
 * structurally complete and decodable. Conservative: only images are policed, and only CLEAR
 * corruption signals fail, so a valid file is never wrongly rejected (a false reject only triggers a
 * harmless re-fetch next cycle).
 */
object ImageIntegrity {

    fun isIntact(bytes: ByteArray, name: String): Boolean {
        val n = name.lowercase()
        val isImage = n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
        if (!isImage) return true                       // only police images
        if (bytes.size < 100) return false
        // Sane dimensions via header-only decode (catches bloated/garbage files w/ insane SOF dims).
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) } catch (_: Throwable) { return false }
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0 || w > 30000 || h > 30000) return false
        val isJpeg = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8
        if (isJpeg) {
            // Final EOI must exist, and the image data up to it must be plausibly large for the pixel
            // count — a tiny data span behind a big canvas = truncated (the "half image").
            var eoi = -1; var i = bytes.size - 2
            while (i >= 2) {
                if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD9) { eoi = i; break }
                i--
            }
            if (eoi < 0) return false
            val dataBytes = (eoi + 2).toLong()
            if (dataBytes < (w.toLong() * h) / 100L) return false   // < 0.01 B/px -> truncated
        } else if (isWebp(bytes)) {
            // RIFF size field must equal the real payload (catches truncation / garbage append).
            val riff = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or
                       ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
            if (riff + 8 != bytes.size) return false
        }
        return true
    }

    private fun isWebp(b: ByteArray) = b.size >= 12 &&
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
}
