package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import java.io.File

/**
 * Frees phone space by managing local videos against the hub backup:
 *   • Videos older than [OLD_AGE_MS] → replaced by a small JPEG poster (with a ▶ badge);
 *     the full video stays on the hub and can be re-downloaded from the hub gallery.
 *   • Videos newer than that         → transcoded in-place to a smaller, lower-quality MP4.
 *
 * SAFETY: nothing is ever deleted or replaced unless the hub is confirmed to hold the full
 * file (matching name and at-least-as-large size). If the hub can't be reached, it does nothing.
 */
class VideoSpaceManager(private val context: Context) {

    data class Summary(val thumbed: Int, val compressed: Int, val skipped: Int, val freedBytes: Long)

    private val prefs = context.getSharedPreferences("video_space_state", Context.MODE_PRIVATE)
    private val store = MediaStoreHelper(context)

    fun process(progress: ((done: Int, total: Int, msg: String) -> Unit)? = null): Summary {
        // Need a reachable hub to verify backups against.
        val ip = ClientForegroundService.liveHubIp
            ?.takeIf { System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L }
            ?: ClientForegroundService.liveHubTailscaleIp
            ?: return Summary(0, 0, 0, 0)
        val port = ClientForegroundService.liveHubPort

        // Build a lookup of what the hub holds: displayName -> (deviceName, sizeBytes, originalDateMs)
        val hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) } catch (_: Exception) { emptyList() }
        if (hubFiles.isEmpty()) return Summary(0, 0, 0, 0)
        val hubByName = HashMap<String, HubInfo>()
        for (f in hubFiles) {
            val existing = hubByName[f.displayName]
            if (existing == null || f.sizeBytes > existing.size)
                hubByName[f.displayName] = HubInfo(f.deviceName, f.sizeBytes, f.lastModifiedMs)
        }

        // One-off: fix any posters already created with today's date back to the original.
        repairPosterDates(hubByName)

        val compressedIds = prefs.getStringSet(KEY_COMPRESSED, emptySet())!!.toMutableSet()
        val videos = queryVideos()
        var thumbed = 0; var compressed = 0; var skipped = 0; var freed = 0L
        val total = videos.size

        videos.forEachIndexed { index, v ->
            try {
                progress?.invoke(index + 1, total, v.name)

                // SAFETY: only act on videos the hub definitely has the full copy of.
                val hubEntry = hubByName[v.name]
                if (hubEntry == null || hubEntry.size < v.size) { skipped++; return@forEachIndexed }

                val ageMs = System.currentTimeMillis() - v.takenMs
                val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)

                if (ageMs > OLD_AGE_MS) {
                    // ── Old video → poster image, delete the video ──
                    val poster = VideoThumbnailer.makePosterJpeg(context, videoUri) ?: run { skipped++; return@forEachIndexed }
                    val posterName = v.name.substringBeforeLast('.') + "_video.jpg"
                    val inserted = insertPoster(v, poster)
                    if (inserted == null) { skipped++; return@forEachIndexed }
                    // Remember how to get the original back (device + name on the hub).
                    rememberRestore(posterName, hubEntry.device, v.name)
                    // Delete the original video (MANAGE_EXTERNAL_STORAGE → direct delete).
                    val deleted = try { context.contentResolver.delete(videoUri, null, null) > 0 } catch (_: Exception) { false }
                    if (deleted) { thumbed++; freed += (v.size - poster.size).coerceAtLeast(0L) }
                    else { skipped++ }
                } else if (v.id.toString() !in compressedIds) {
                    // ── Recent video → transcode smaller, replace in place ──
                    val tmp = File(context.cacheDir, "vtrans_${v.id}.mp4")
                    try {
                        val okT = VideoTranscoder.transcode(context, videoUri, tmp.absolutePath)
                        if (okT && tmp.exists() && tmp.length() in 1 until (v.size * 9 / 10)) {
                            val bytes = tmp.readBytes()
                            store.replaceFile(v.id, "video/mp4", bytes)
                            compressed++; freed += (v.size - bytes.size).coerceAtLeast(0L)
                        }
                        compressedIds.add(v.id.toString())
                    } finally { tmp.delete() }
                }
            } catch (_: Throwable) { skipped++ }
        }

        prefs.edit().putStringSet(KEY_COMPRESSED, compressedIds).apply()
        return Summary(thumbed, compressed, skipped, freed)
    }

    // ── MediaStore helpers ──────────────────────────────────────────────────────

    private data class VideoRow(
        val id: Long, val name: String, val size: Long, val takenMs: Long, val relativePath: String,
        val dateAddedSec: Long, val dateModifiedSec: Long
    )

    private fun queryVideos(): List<VideoRow> {
        val out = ArrayList<VideoRow>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Video.Media.IS_PENDING} = 0", null,
            "${MediaStore.Video.Media.DATE_ADDED} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iSz = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDa = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val iDt = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val iDm = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val iRp = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val da = c.getLong(iDa)
                val takenMs = c.getLong(iDt).takeIf { it > 0 } ?: (da * 1000L)
                out.add(VideoRow(c.getLong(iId), name, c.getLong(iSz), takenMs,
                    c.getString(iRp) ?: "DCIM/", da, c.getLong(iDm)))
            }
        }
        return out
    }

    /**
     * Inserts the poster JPEG as an image so it shows in the gallery EXACTLY where the video was —
     * preserving the original DATE_TAKEN / DATE_ADDED / DATE_MODIFIED. MediaStore stamps
     * DATE_ADDED = now on insert, so we must force the original dates back in a follow-up update
     * after the IS_PENDING transition (which also resets them).
     */
    private fun insertPoster(v: VideoRow, jpeg: ByteArray): Uri? {
        // Images may only go under DCIM/ or Pictures/.
        val rel = if (v.relativePath.startsWith("DCIM", true) || v.relativePath.startsWith("Pictures", true))
            v.relativePath else "Pictures/"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, v.name.substringBeforeLast('.') + "_video.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, rel)
            if (v.takenMs > 0)         put(MediaStore.Images.Media.DATE_TAKEN, v.takenMs)
            if (v.dateAddedSec > 0)    put(MediaStore.Images.Media.DATE_ADDED, v.dateAddedSec)
            if (v.dateModifiedSec > 0) put(MediaStore.Images.Media.DATE_MODIFIED, v.dateModifiedSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return try {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.SIZE, jpeg.size.toLong())
                    if (v.takenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, v.takenMs)
                }, null, null)
                // Force original dates AFTER publishing — Android resets them during the transition.
                context.contentResolver.update(uri, ContentValues().apply {
                    if (v.takenMs > 0)         put(MediaStore.Images.Media.DATE_TAKEN, v.takenMs)
                    if (v.dateAddedSec > 0)    put(MediaStore.Images.Media.DATE_ADDED, v.dateAddedSec)
                    if (v.dateModifiedSec > 0) put(MediaStore.Images.Media.DATE_MODIFIED, v.dateModifiedSec)
                }, null, null)
            }
            uri
        } catch (_: Exception) { null }
    }

    private fun rememberRestore(posterName: String, device: String, videoName: String) {
        val set = prefs.getStringSet(KEY_RESTORE, emptySet())!!.toMutableSet()
        set.add("$posterName|$device|$videoName")
        prefs.edit().putStringSet(KEY_RESTORE, set).apply()
    }

    private data class HubInfo(val device: String, val size: Long, val lastModifiedMs: Long)

    /** Repairs posters that were created before the date-preservation fix: restores each poster's
     *  DATE_TAKEN/ADDED/MODIFIED to the original video's date (taken from the hub's record). */
    private fun repairPosterDates(hubByName: Map<String, HubInfo>) {
        val restore = prefs.getStringSet(KEY_RESTORE, emptySet()) ?: return
        for (entry in restore) {
            val parts = entry.split("|")
            if (parts.size < 3) continue
            val posterName = parts[0]; val videoName = parts[2]
            // Prefer a date embedded in the filename (most reliable), then the hub's record.
            val origMs = parseDateFromName(videoName).takeIf { it > 0 }
                ?: hubByName[videoName]?.lastModifiedMs ?: continue
            if (origMs <= 0) continue
            val id = findImageIdByName(posterName) ?: continue
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            try {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, origMs)
                    put(MediaStore.Images.Media.DATE_ADDED, origMs / 1000)
                    put(MediaStore.Images.Media.DATE_MODIFIED, origMs / 1000)
                }, null, null)
            } catch (_: Exception) {}
        }
    }

    /** Best-effort date from a media filename: VID-20220606-*, IMG_20230101_*, or an epoch-ms name. */
    private fun parseDateFromName(name: String): Long {
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(name)?.let { m ->
            val (y, mo, d) = m.destructured
            try {
                return java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        name.substringBeforeLast('.').toLongOrNull()?.let {
            if (it in 1_000_000_000_000L..9_999_999_999_999L) return it   // 13-digit epoch ms
        }
        return 0L
    }

    private fun findImageIdByName(displayName: String): Long? {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(displayName), null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    companion object {
        private const val OLD_AGE_MS = 30L * 24 * 60 * 60 * 1000   // 1 month
        private const val KEY_COMPRESSED = "compressed_video_ids"
        private const val KEY_RESTORE    = "poster_restore_map"
    }
}
