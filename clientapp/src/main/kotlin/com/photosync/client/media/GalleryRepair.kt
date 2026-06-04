package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repairs gallery damage left by the OLD hub-side WebP conversion.
 *
 * That conversion ran on the hub's Android < 12, where ExifInterface cannot write EXIF to WebP.
 * The result: files left on the phone as WebP with a double extension (`foo.jpg.webp`) and with
 * ALL metadata stripped — no date, no orientation, no GPS. The gallery shows them under "today"
 * (no DATE_TAKEN) and sometimes sideways (no orientation).
 *
 * The pristine originals are still on the hub USB. This restores each damaged file from its hub
 * original, which carries the correct pixels, orientation, date and full EXIF. After restore the
 * file is a clean `foo.jpg` again; the client's normal WebP pass will re-compress it correctly
 * (client-side, where EXIF-to-WebP works).
 *
 * Runs on hub connect, before any other transfer, so the gallery is tidied first.
 */
class GalleryRepair(private val context: Context) {

    data class Summary(val restored: Int, val failed: Int, val damagedRemaining: Int)

    private data class Row(val id: Long, val name: String, val relativePath: String,
                           val dateAddedSec: Long)

    fun repair(log: (String) -> Unit): Summary {
        val ip = ClientForegroundService.liveHubIp
            ?.takeIf { System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L }
            ?: ClientForegroundService.liveHubTailscaleIp ?: return Summary(0, 0, 0)
        val port = ClientForegroundService.liveHubPort

        val damaged = queryDamaged()
        val misdatedVideos = queryMisdatedVideos()
        if (damaged.isEmpty() && misdatedVideos.isEmpty()) return Summary(0, 0, 0)

        val hubFiles = try { HubFilesClient.fetchFiles(ip, port, 10_000) } catch (_: Exception) { emptyList() }
        if (hubFiles.isEmpty()) {
            log("Repair: hub unreachable, ${damaged.size + misdatedVideos.size} file(s) deferred")
            return Summary(0, 0, damaged.size + misdatedVideos.size)
        }
        val hubByName = hubFiles.associateBy { it.displayName }

        var restored = 0; var failed = 0

        // ── Images: restore .jpg.webp files damaged by old hub-side conversion ──
        if (damaged.isNotEmpty()) {
            log("Repair: ${damaged.size} damaged image(s) — restoring originals from hub…")
            for ((index, row) in damaged.withIndex()) {
                try {
                    val originalName = row.name.replace(Regex("\\.webp$", RegexOption.IGNORE_CASE), "")
                    val hubEntry = hubByName[originalName] ?: hubByName[row.name]
                    if (hubEntry == null) { failed++; continue }
                    val orig = HubFilesClient.fetchFile(ip, port, hubEntry.deviceName, originalName)
                    if (orig == null || orig.size < 100) { failed++; continue }
                    if (restoreOriginal(row, originalName, orig, isVideo = false)) {
                        restored++
                        if (restored % 10 == 0 || index == damaged.lastIndex)
                            log("↺ Restored image ${index + 1}/${damaged.size}")
                    } else failed++
                } catch (_: Throwable) { failed++ }
            }
        }

        // ── Videos: restore originals whose DATE_TAKEN was wrongly stamped (the wrong date
        //    is baked into the transcoded MP4's internal creation_time, so only the pristine
        //    hub original — with the correct internal date — fixes it permanently). ──
        if (misdatedVideos.isNotEmpty()) {
            log("Repair: ${misdatedVideos.size} misdated video(s) — restoring originals from hub…")
            for ((index, row) in misdatedVideos.withIndex()) {
                val tmp = java.io.File(context.cacheDir, "vrestore_${row.id}.mp4")
                try {
                    val hubEntry = hubByName[row.name] ?: run { failed++; continue }
                    // Stream the original to a temp file (no full-video ByteArray → no OOM).
                    if (!HubFilesClient.fetchFileToFile(ip, port, hubEntry.deviceName, row.name, tmp)) {
                        failed++; continue
                    }
                    if (restoreVideoFromFile(row, tmp)) {
                        restored++
                        log("↺ Restored video ${index + 1}/${misdatedVideos.size}: ${row.name}")
                    } else failed++
                } catch (_: Throwable) { failed++ }
                finally { runCatching { tmp.delete() } }
            }
        }

        log("Repair: restored $restored, failed $failed")
        return Summary(restored, failed, queryDamaged().size + queryMisdatedVideos().size)
    }

    /** Deletes the damaged/misdated row and re-inserts the hub original under the clean name. */
    private fun restoreOriginal(row: Row, originalName: String, bytes: ByteArray, isVideo: Boolean): Boolean {
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val oldUri = ContentUris.withAppendedId(collection, row.id)

        // Correct date: original's EXIF (images) → filename timestamp → DATE_ADDED.
        // For videos the restored original carries the correct internal creation_time, so the
        // media scanner keeps the date; we set DATE_TAKEN from the filename for immediacy too.
        val dateMs = (if (isVideo) 0L else readExifDate(bytes)).takeIf { it > 0 }
            ?: parseDateFromName(originalName).takeIf { it > 0 }
            ?: (row.dateAddedSec * 1000L)
        val dateSec = if (dateMs > 0) dateMs / 1000L else 0L

        // Delete first so the insert can reuse the clean name without a "(1)" suffix.
        val deleted = try { context.contentResolver.delete(oldUri, null, null) > 0 }
                      catch (_: Exception) { false }
        if (!deleted) return false

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, row.relativePath.ifEmpty { if (isVideo) "DCIM/Camera/" else "DCIM/" })
            if (dateMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
            if (dateSec > 0) {
                put(MediaStore.MediaColumns.DATE_ADDED, dateSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = try {
            context.contentResolver.insert(collection, values)
        } catch (_: Exception) { null } ?: return false

        return try {
            context.contentResolver.openOutputStream(newUri)?.use { it.write(bytes) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.SIZE, bytes.size.toLong())
                    if (dateMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(MediaStore.MediaColumns.DATE_ADDED, dateSec)
                                       put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec) }
                }, null, null)
                // Second update — Samsung resets dates during the IS_PENDING transition.
                if (dateMs > 0) context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(MediaStore.MediaColumns.DATE_ADDED, dateSec)
                                       put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec) }
                }, null, null)
            }
            true
        } catch (_: Exception) {
            runCatching { context.contentResolver.delete(newUri, null, null) }
            false
        }
    }

    /** Restores a video from a temp file (streamed copy, no full ByteArray) into MediaStore. */
    private fun restoreVideoFromFile(row: Row, src: java.io.File): Boolean {
        val oldUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, row.id)
        val dateMs = parseDateFromName(row.name).takeIf { it > 0 } ?: (row.dateAddedSec * 1000L)
        val dateSec = if (dateMs > 0) dateMs / 1000L else 0L

        val deleted = try { context.contentResolver.delete(oldUri, null, null) > 0 }
                      catch (_: Exception) { false }
        if (!deleted) return false

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, row.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, row.relativePath.ifEmpty { "DCIM/Camera/" })
            if (dateMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
            if (dateSec > 0) { put(MediaStore.MediaColumns.DATE_ADDED, dateSec)
                               put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = try {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) { null } ?: return false

        return try {
            context.contentResolver.openOutputStream(newUri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.SIZE, src.length())
                    if (dateMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(MediaStore.MediaColumns.DATE_ADDED, dateSec)
                                       put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec) }
                }, null, null)
                if (dateMs > 0) context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(MediaStore.MediaColumns.DATE_ADDED, dateSec)
                                       put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec) }
                }, null, null)
            }
            true
        } catch (_: Exception) {
            runCatching { context.contentResolver.delete(newUri, null, null) }
            false
        }
    }

    /** Videos whose DATE_TAKEN is >24h off the filename date (wrongly stamped by old build). */
    private fun queryMisdatedVideos(): List<Row> {
        val out = ArrayList<Row>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_TAKEN
        )
        val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.MediaColumns.IS_PENDING} = 0" else null
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iRp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iDa = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iDt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val nameDate = parseDateFromName(name)
                if (nameDate <= 0) continue              // no reliable date in name → leave it
                val taken = c.getLong(iDt)               // ms
                if (taken > 0 && Math.abs(taken - nameDate) <= 24 * 60 * 60 * 1000L) continue
                out += Row(c.getLong(iId), name, c.getString(iRp) ?: "DCIM/Camera/", c.getLong(iDa))
            }
        }
        return out
    }

    /** Date from filename: YYYYMMDD_HHMMSS (full) → YYYYMMDD (midnight) → 13-digit epoch ms. */
    private fun parseDateFromName(name: String): Long {
        val stem = name.substringBeforeLast('.')
        Regex("(20\\d{2})(\\d{2})(\\d{2})[_\\-](\\d{2})(\\d{2})(\\d{2})").find(stem)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            try {
                return java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(),
                        h.toInt(), mi.toInt(), s.toInt())
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(stem)?.let { m ->
            val (y, mo, d) = m.destructured
            try {
                return java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        stem.toLongOrNull()?.let { if (it in 1_000_000_000_000L..9_999_999_999_999L) return it }
        return 0L
    }

    private fun readExifDate(bytes: ByteArray): Long {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return 0L
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** Damaged = display name has a double image extension ending in .webp (e.g. foo.jpg.webp). */
    private fun queryDamaged(): List<Row> {
        val out = ArrayList<Row>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.MediaColumns.IS_PENDING} = 0" else null
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iRp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iDa = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val lower = name.lowercase()
                if (lower.endsWith(".jpg.webp") || lower.endsWith(".jpeg.webp") ||
                    lower.endsWith(".png.webp")) {
                    out += Row(c.getLong(iId), name, c.getString(iRp) ?: "DCIM/", c.getLong(iDa))
                }
            }
        }
        return out
    }
}
