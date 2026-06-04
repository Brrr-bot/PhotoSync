package com.photosync.client.media

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Rewrites the creation_time / modification_time fields inside an MP4's header atoms
 * (mvhd, tkhd, mdhd) so the date the gallery reads from the file itself is correct.
 *
 * An old transcode baked the wrong (processing-time) date into these atoms; Samsung's media
 * scanner reads them and overrides MediaStore's DATE_TAKEN, so fixing the database alone
 * reverts. Editing the atoms fixes the root.
 *
 * Operates on the content URI's file descriptor (works on Android 10+ with MANAGE_EXTERNAL_STORAGE
 * without needing the deprecated DATA path). Only timestamp fields are touched (4/8 bytes each) —
 * no structural change, so the video stays playable.
 *
 * MP4 timestamps are seconds since 1904-01-01 UTC (Unix epoch + 2082844800).
 */
object Mp4DateEditor {

    private const val EPOCH_OFFSET = 2_082_844_800L

    /** Patches all mvhd/tkhd/mdhd timestamps in [uri] to [dateMs]. Returns true if any were written. */
    fun setCreationTime(context: Context, uri: Uri, dateMs: Long): Boolean {
        if (dateMs <= 0) return false
        val mp4Time = dateMs / 1000L + EPOCH_OFFSET
        var patched = 0
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val fd = pfd.fileDescriptor
                val readCh = FileInputStream(fd).channel
                val writeCh = FileOutputStream(fd).channel
                val fileLen = readCh.size()

                val moov = findTopLevelBox(readCh, fileLen, "moov") ?: return false
                val (moovStart, moovEnd) = moov
                val scanLen = (moovEnd - moovStart).toInt().coerceAtMost(8 * 1024 * 1024)
                val buf = ByteBuffer.allocate(scanLen)
                readCh.read(buf, moovStart)
                val a = buf.array()
                var i = 0
                while (i < scanLen - 8) {
                    val type = "" + a[i + 4].toInt().toChar() + a[i + 5].toInt().toChar() +
                               a[i + 6].toInt().toChar() + a[i + 7].toInt().toChar()
                    if (type == "mvhd" || type == "tkhd" || type == "mdhd") {
                        val boxStart = moovStart + i
                        if (patchAtom(readCh, writeCh, boxStart, mp4Time)) patched++
                    }
                    i++
                }
            }
        } catch (_: Throwable) { return false }
        return patched > 0
    }

    private fun patchAtom(readCh: FileChannel, writeCh: FileChannel, boxStart: Long, mp4Time: Long): Boolean {
        return try {
            val verBuf = ByteBuffer.allocate(1)
            readCh.read(verBuf, boxStart + 8)        // version byte
            val version = verBuf.get(0).toInt() and 0xFF
            val fieldStart = boxStart + 12           // after version(1)+flags(3)
            if (version == 1) {
                val out = ByteBuffer.allocate(16)
                out.putLong(mp4Time); out.putLong(mp4Time); out.flip()
                writeCh.write(out, fieldStart)
            } else {
                val out = ByteBuffer.allocate(8)
                out.putInt((mp4Time and 0xFFFFFFFFL).toInt())
                out.putInt((mp4Time and 0xFFFFFFFFL).toInt())
                out.flip()
                writeCh.write(out, fieldStart)
            }
            true
        } catch (_: Exception) { false }
    }

    private fun findTopLevelBox(ch: FileChannel, fileLen: Long, wanted: String): Pair<Long, Long>? {
        var pos = 0L
        val hdr = ByteBuffer.allocate(8)
        while (pos + 8 <= fileLen) {
            hdr.clear()
            if (ch.read(hdr, pos) < 8) return null
            val a = hdr.array()
            var size = ((a[0].toLong() and 0xFF) shl 24) or ((a[1].toLong() and 0xFF) shl 16) or
                       ((a[2].toLong() and 0xFF) shl 8) or (a[3].toLong() and 0xFF)
            val type = "" + a[4].toInt().toChar() + a[5].toInt().toChar() +
                       a[6].toInt().toChar() + a[7].toInt().toChar()
            var headerLen = 8L
            if (size == 1L) {
                val big = ByteBuffer.allocate(8); ch.read(big, pos + 8); size = big.getLong(0); headerLen = 16L
            } else if (size == 0L) {
                size = fileLen - pos
            }
            if (size < headerLen) return null
            if (type == wanted) return Pair(pos, pos + size)
            pos += size
        }
        return null
    }
}
