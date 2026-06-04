package com.photosync.client.media

import java.io.RandomAccessFile

/**
 * Rewrites the creation_time / modification_time fields inside an MP4's header atoms
 * (mvhd, tkhd, mdhd) so the date the gallery reads from the file itself is correct.
 *
 * Needed because an old transcode baked the wrong (processing-time) date into these atoms;
 * Samsung's media scanner reads them and overrides MediaStore's DATE_TAKEN, so fixing the
 * database alone doesn't stick. Editing the atoms fixes the root.
 *
 * Only the timestamp fields are touched (4 or 8 bytes each) — no structural change, so the
 * video stays playable. Works on the real file path with MANAGE_EXTERNAL_STORAGE.
 *
 * MP4 timestamps are seconds since 1904-01-01 UTC (Unix epoch + 2082844800).
 */
object Mp4DateEditor {

    private const val EPOCH_OFFSET = 2_082_844_800L  // seconds between 1904-01-01 and 1970-01-01

    /** Patches all mvhd/tkhd/mdhd timestamps in [path] to [dateMs]. Returns true if any were written. */
    fun setCreationTime(path: String, dateMs: Long): Boolean {
        if (dateMs <= 0) return false
        val mp4Time = dateMs / 1000L + EPOCH_OFFSET
        return try {
            RandomAccessFile(path, "rw").use { raf ->
                val fileLen = raf.length()
                // Find the top-level 'moov' box; header atoms live only inside it (never in mdat),
                // so scanning within moov avoids false matches in the media payload.
                val moov = findTopLevelBox(raf, fileLen, "moov") ?: return false
                val (moovStart, moovEnd) = moov
                var patched = 0
                // Scan moov's bytes for header-atom type signatures.
                val buf = ByteArray((moovEnd - moovStart).toInt().coerceAtMost(8 * 1024 * 1024))
                raf.seek(moovStart)
                raf.readFully(buf, 0, buf.size)
                var i = 0
                while (i < buf.size - 8) {
                    val type = "" + buf[i + 4].toInt().toChar() + buf[i + 5].toInt().toChar() +
                               buf[i + 6].toInt().toChar() + buf[i + 7].toInt().toChar()
                    if (type == "mvhd" || type == "tkhd" || type == "mdhd") {
                        val boxStart = moovStart + i           // points at size field
                        if (patchAtomTimestamps(raf, boxStart, mp4Time)) patched++
                    }
                    i++
                }
                patched > 0
            }
        } catch (_: Exception) { false }
    }

    /**
     * Patches creation_time + modification_time of a full-box header at [boxStart]
     * (boxStart points at the 4-byte size, followed by the 4-byte type).
     * Layout after the 8-byte box header: version(1) flags(3) creation(4|8) modification(4|8).
     */
    private fun patchAtomTimestamps(raf: RandomAccessFile, boxStart: Long, mp4Time: Long): Boolean {
        return try {
            raf.seek(boxStart + 8)            // version byte
            val version = raf.readUnsignedByte()
            val fieldStart = boxStart + 12    // after version(1)+flags(3)
            if (version == 1) {
                raf.seek(fieldStart)
                raf.writeLong(mp4Time)        // creation_time (64-bit)
                raf.writeLong(mp4Time)        // modification_time (64-bit)
            } else {
                raf.seek(fieldStart)
                raf.writeInt((mp4Time and 0xFFFFFFFFL).toInt())  // creation_time (32-bit)
                raf.writeInt((mp4Time and 0xFFFFFFFFL).toInt())  // modification_time (32-bit)
            }
            true
        } catch (_: Exception) { false }
    }

    /** Walks the top-level box list and returns [start, end) of the first box of [wanted]. */
    private fun findTopLevelBox(raf: RandomAccessFile, fileLen: Long, wanted: String): Pair<Long, Long>? {
        var pos = 0L
        val header = ByteArray(8)
        while (pos + 8 <= fileLen) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            var size = ((header[0].toLong() and 0xFF) shl 24) or
                       ((header[1].toLong() and 0xFF) shl 16) or
                       ((header[2].toLong() and 0xFF) shl 8) or
                       (header[3].toLong() and 0xFF)
            val type = "" + header[4].toInt().toChar() + header[5].toInt().toChar() +
                       header[6].toInt().toChar() + header[7].toInt().toChar()
            var headerLen = 8L
            if (size == 1L) {            // 64-bit largesize
                raf.seek(pos + 8)
                size = raf.readLong()
                headerLen = 16L
            } else if (size == 0L) {     // extends to EOF
                size = fileLen - pos
            }
            if (size < headerLen) return null
            if (type == wanted) return Pair(pos, pos + size)
            pos += size
        }
        return null
    }
}
