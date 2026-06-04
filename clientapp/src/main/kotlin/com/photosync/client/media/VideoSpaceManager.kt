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
import android.media.MediaMetadataRetriever
import com.photosync.client.util.RemoteLogger

/**
 * Frees phone space by managing local videos against the hub backup:
 *   - Videos older than OLD_AGE_MS  -> replaced by a small JPEG poster (with a play badge);
 *     the full video stays on the hub and can be re-downloaded from the hub gallery.
 *   - Videos newer than that        -> transcoded in-place to a smaller, lower-quality MP4.
 *
 * SAFETY: nothing is ever deleted or replaced unless the hub is confirmed to hold the full
 * file (matching name and at-least-as-large size). If the hub cannot be reached, it does nothing.
 */
class VideoSpaceManager(private val context: Context) {

    data class Summary(val thumbed: Int, val compressed: Int, val skipped: Int, val freedBytes: Long)

    private val prefs = context.getSharedPreferences("video_space_state", Context.MODE_PRIVATE)
    private val store = MediaStoreHelper(context)

    fun process(progress: ((done: Int, total: Int, msg: String) -> Unit)? = null): Summary {
        // Run date-repair before the hub check — it's a purely local MediaStore operation.
        repairCompressedVideoDates()

        val ip = ClientForegroundService.liveHubIp
            ?.takeIf { System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L }
            ?: ClientForegroundService.liveHubTailscaleIp
            ?: return Summary(0, 0, 0, 0)
        val port = ClientForegroundService.liveHubPort

        val hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) } catch (_: Exception) { emptyList() }
        if (hubFiles.isEmpty()) return Summary(0, 0, 0, 0)
        val hubByName = HashMap<String, HubInfo>()
        for (f in hubFiles) {
            val existing = hubByName[f.displayName]
            if (existing == null || f.sizeBytes > existing.size)
                hubByName[f.displayName] = HubInfo(f.deviceName, f.sizeBytes, f.lastModifiedMs)
        }

        repairPosterDates(hubByName)

        // Legacy ID-based tracking (pre-v316) — still respected to avoid re-compressing.
        val compressedIds = prefs.getStringSet(KEY_COMPRESSED, emptySet())!!
        // Name-based tracking (v316+) — used going forward.
        val compressedNames = prefs.getStringSet(KEY_COMPRESSED_NAMES, emptySet())!!.toMutableSet()

        val videos = queryVideos()
        var thumbed = 0; var compressed = 0; var skipped = 0; var freed = 0L
        val total = videos.size

        videos.forEachIndexed { index, v ->
            try {
                progress?.invoke(index + 1, total, v.name)

                val hubEntry = hubByName[v.name]
                if (hubEntry == null || hubEntry.size < v.size) { skipped++; return@forEachIndexed }

                val ageMs = System.currentTimeMillis() - v.takenMs
                val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)

                if (ageMs > OLD_AGE_MS) {
                    // Old video -> replace with poster JPEG
                    val poster = VideoThumbnailer.makePosterJpeg(context, videoUri) ?: run { skipped++; return@forEachIndexed }
                    val posterName = v.name.substringBeforeLast('.') + ".jpg"
                    val takenMs = parseDateFromName(v.name).takeIf { it > 0 }
                        ?: readVideoDate(videoUri)
                        ?: v.dateAddedSec.takeIf { it > 0 }?.times(1000L)
                        ?: v.takenMs
                    val stampedPoster = VideoThumbnailer.stampPosterExif(poster, takenMs)
                    insertPoster(v, stampedPoster, posterName, takenMs) ?: run { skipped++; return@forEachIndexed }
                    rememberRestore(posterName, hubEntry.device, v.name)
                    val deleted = try { context.contentResolver.delete(videoUri, null, null) > 0 } catch (_: Exception) { false }
                    if (deleted) { thumbed++; freed += (v.size - stampedPoster.size).coerceAtLeast(0L) }
                    else { skipped++ }
                } else if (v.name !in compressedNames && v.id.toString() !in compressedIds) {
                    // Recent video -> transcode to smaller MP4, preserve filename + original date
                    val tmp = File(context.cacheDir, "vtrans_${v.id}.mp4")
                    try {
                        val okT = VideoTranscoder.transcode(context, videoUri, tmp.absolutePath)
                        if (okT && tmp.exists() && tmp.length() in 1 until (v.size * 9 / 10)) {
                            val bytes = tmp.readBytes()
                            val savedBytes = v.size - bytes.size
                            if (replaceCompressedVideo(v, bytes)) {
                                compressed++; freed += savedBytes.coerceAtLeast(0L)
                            }
                        }
                    } finally { tmp.delete() }
                    compressedNames.add(v.name)   // mark regardless to avoid repeated transcode
                }
            } catch (_: Throwable) { skipped++ }
        }

        prefs.edit().putStringSet(KEY_COMPRESSED_NAMES, compressedNames).apply()
        return Summary(thumbed, compressed, skipped, freed)
    }

    // ---- MediaStore helpers ---------------------------------------------------

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
     * Maps a video source folder to the right destination for its poster image.
     * Always returns a DCIM/ or Pictures/ path (the only valid image locations on Android).
     */
    private fun imageFolderFor(videoRelativePath: String): String {
        val p = videoRelativePath.trimEnd('/')
        return when {
            p.startsWith("DCIM", ignoreCase = true) ||
            p.startsWith("Pictures", ignoreCase = true) -> "$p/"
            p.contains("WhatsApp", ignoreCase = true)   -> "Pictures/WhatsApp Images/"
            p.contains("Telegram", ignoreCase = true)   -> "Pictures/Telegram Images/"
            p.startsWith("Movies", ignoreCase = true)   ->
                "Pictures/" + p.removePrefix("Movies").trimStart('/').let { if (it.isEmpty()) "" else "$it/" }
            else -> "Pictures/"
        }
    }

    /**
     * Inserts the poster JPEG in the correct gallery folder with the original dates.
     * IS_PENDING=1 on insert so this app owns the row and DATE_TAKEN sticks on Samsung 13+.
     */
    private fun insertPoster(v: VideoRow, jpeg: ByteArray, posterName: String, takenMs: Long): Uri? {
        val rel = imageFolderFor(v.relativePath)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, posterName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, rel)
            if (takenMs > 0)         put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
            if (v.dateAddedSec > 0)    put(MediaStore.Images.Media.DATE_ADDED, v.dateAddedSec)
            if (v.dateModifiedSec > 0) put(MediaStore.Images.Media.DATE_MODIFIED, v.dateModifiedSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        // Track poster names so MediaStoreHelper.getMediaSince can exclude them from upload.
        val posterNames = prefs.getStringSet(KEY_POSTER_NAMES, emptySet())!!.toMutableSet()
        posterNames.add(posterName)
        prefs.edit().putStringSet(KEY_POSTER_NAMES, posterNames).apply()

        return try {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.SIZE, jpeg.size.toLong())
                    if (takenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
                }, null, null)
                // Force dates again after IS_PENDING transition — Android resets them.
                context.contentResolver.update(uri, ContentValues().apply {
                    if (takenMs > 0)         put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
                    if (v.dateAddedSec > 0)    put(MediaStore.Images.Media.DATE_ADDED, v.dateAddedSec)
                    if (v.dateModifiedSec > 0) put(MediaStore.Images.Media.DATE_MODIFIED, v.dateModifiedSec)
                }, null, null)
            }
            uri
        } catch (_: Exception) { null }
    }

    /**
     * Replaces a compressed video with [compressedBytes], preserving the exact original
     * filename, folder, and date.  Uses delete+reinsert so this app owns the new row and
     * DATE_TAKEN sticks on Samsung Android 13+.
     *
     * Date priority: parsed from filename > original DATE_TAKEN > DATE_ADDED (seconds).
     * Parsing from filename handles the very common case where DATE_TAKEN is 0 (e.g. WhatsApp
     * videos) but the filename encodes the real shoot date (VID-20220606-WA0009.mp4).
     */
    private fun replaceCompressedVideo(v: VideoRow, compressedBytes: ByteArray): Boolean {
        val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)
        val takenMs = parseDateFromName(v.name).takeIf { it > 0 }
            ?: v.takenMs.takeIf { it > 0 }
            ?: (v.dateAddedSec * 1000L)

        // Delete first so the re-insert uses the exact original filename without a "(1)" suffix.
        // Requires MANAGE_EXTERNAL_STORAGE which the VideoSpaceManager workflow requires.
        val deleted = try { context.contentResolver.delete(videoUri, null, null) > 0 } catch (_: Exception) { false }
        if (!deleted) return false

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, v.name)           // exact original filename
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, v.relativePath)  // exact original folder
            put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
            if (v.dateAddedSec > 0)    put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
            if (v.dateModifiedSec > 0) put(MediaStore.Video.Media.DATE_MODIFIED, v.dateModifiedSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val newUri = try {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) { null } ?: return false

        return try {
            context.contentResolver.openOutputStream(newUri)?.use { it.write(compressedBytes) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.SIZE, compressedBytes.size.toLong())
                    put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
                    if (v.dateAddedSec > 0)    put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
                    if (v.dateModifiedSec > 0) put(MediaStore.Video.Media.DATE_MODIFIED, v.dateModifiedSec)
                }, null, null)
                // Second update — Samsung resets dates during IS_PENDING transition.
                context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
                    if (v.dateAddedSec > 0)    put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
                    if (v.dateModifiedSec > 0) put(MediaStore.Video.Media.DATE_MODIFIED, v.dateModifiedSec)
                }, null, null)
            }
            true
        } catch (_: Exception) {
            runCatching { context.contentResolver.delete(newUri, null, null) }
            false
        }
    }

    private fun rememberRestore(posterName: String, device: String, videoName: String) {
        val set = prefs.getStringSet(KEY_RESTORE, emptySet())!!.toMutableSet()
        set.add("$posterName|$device|$videoName")
        prefs.edit().putStringSet(KEY_RESTORE, set).apply()
    }

    private data class HubInfo(val device: String, val size: Long, val lastModifiedMs: Long)

    /**
     * One-off repair: for every poster in KEY_RESTORE not yet in KEY_REPAIRED, reads its bytes,
     * deletes the old row, and re-inserts as an app-owned file with the correct date + EXIF marker.
     * Also strips the legacy "_video" suffix from poster names created before v317.
     */
    private fun repairPosterDates(hubByName: Map<String, HubInfo>) {
        val restore  = prefs.getStringSet(KEY_RESTORE,  emptySet()) ?: return
        val repaired = prefs.getStringSet(KEY_REPAIRED, emptySet())!!.toMutableSet()
        val newRepaired = mutableSetOf<String>()

        for (entry in restore) {
            val parts = entry.split("|")
            if (parts.size < 3) continue
            val posterName = parts[0]; val videoName = parts[2]
            if (posterName in repaired) continue

            val origMs = parseDateFromName(videoName).takeIf { it > 0 }
                ?: hubByName[videoName]?.lastModifiedMs ?: continue
            if (origMs <= 0) continue

            val id = findImageIdByName(posterName) ?: continue
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) { null } ?: continue

            val stampedBytes = VideoThumbnailer.stampPosterExif(bytes, origMs)

            var relativePath = "DCIM/"
            context.contentResolver.query(uri,
                arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)
                ?.use { c -> if (c.moveToFirst()) relativePath = c.getString(0) ?: "DCIM/" }

            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { continue }

            // Strip legacy _video suffix from pre-v317 poster names
            val cleanName = if (posterName.endsWith("_video.jpg"))
                posterName.removeSuffix("_video.jpg") + ".jpg" else posterName

            val origSec = origMs / 1000L
            val newValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, cleanName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.DATE_TAKEN, origMs)
                put(MediaStore.Images.Media.DATE_ADDED, origSec)
                put(MediaStore.Images.Media.DATE_MODIFIED, origSec)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val newUri = try {
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newValues)
            } catch (_: Exception) { null } ?: continue

            try {
                context.contentResolver.openOutputStream(newUri)?.use { it.write(stampedBytes) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(newUri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                        put(MediaStore.Images.Media.SIZE, stampedBytes.size.toLong())
                        put(MediaStore.Images.Media.DATE_TAKEN, origMs)
                        put(MediaStore.Images.Media.DATE_ADDED, origSec)
                        put(MediaStore.Images.Media.DATE_MODIFIED, origSec)
                    }, null, null)
                    context.contentResolver.update(newUri, ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_TAKEN, origMs)
                        put(MediaStore.Images.Media.DATE_ADDED, origSec)
                        put(MediaStore.Images.Media.DATE_MODIFIED, origSec)
                    }, null, null)
                }
                val posterNames = prefs.getStringSet(KEY_POSTER_NAMES, emptySet())!!.toMutableSet()
                posterNames.remove(posterName); posterNames.add(cleanName)
                prefs.edit().putStringSet(KEY_POSTER_NAMES, posterNames).apply()
                newRepaired.add(posterName)
            } catch (_: Exception) {}
        }

        if (newRepaired.isNotEmpty()) {
            repaired.addAll(newRepaired)
            prefs.edit().putStringSet(KEY_REPAIRED, repaired).apply()
        }
    }

    /** Reads creation date from the video file's own metadata — bypasses MediaStore DATE_TAKEN
     * which Samsung corrupts during IS_PENDING transitions. */
    private fun readVideoDate(uri: Uri): Long? {
        return try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
                for (fmt in arrayOf("yyyyMMdd'T'HHmmss.SSSZ", "yyyyMMddTHHmmssZ", "yyyyMMddTHHmmss'Z'")) {
                    try {
                        val ms = java.text.SimpleDateFormat(fmt, java.util.Locale.US).parse(raw)?.time
                        if (ms != null && ms > 0) return ms
                    } catch (_: Exception) {}
                }
                null
            }
        } catch (_: Exception) { null }
    }
    /** Best-effort date from a media filename: VID-20220606-*, IMG_20230101_*, or 13-digit epoch ms. */
    private fun parseDateFromName(name: String): Long {
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(name)?.let { m ->
            val (y, mo, d) = m.destructured
            try {
                return java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        name.substringBeforeLast('.').toLongOrNull()?.let {
            if (it in 1_000_000_000_000L..9_999_999_999_999L) return it
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


    /**
     * One-off repair: finds compressed videos whose DATE_TAKEN doesn't match the date in their
     * filename (left wrong by the old replaceFile Strategy A code which couldn't update dates on
     * Camera-owned rows). Reads each video, deletes the MediaStore row, re-inserts as an
     * app-owned row with the correct date from parseDateFromName().
     *
     * Only repairs videos where we can confidently parse the correct date from the filename
     * AND the stored date is wrong by more than 24 hours.  Tracked in KEY_VIDEO_DATES_REPAIRED
     * so each video is only attempted once.
     */
    internal fun repairCompressedVideoDates() {
        val repaired = prefs.getStringSet(KEY_VIDEO_DATES_REPAIRED, emptySet())!!.toMutableSet()
        val videos = queryVideos()
        val toFix = videos.count { v ->
            v.name !in repaired &&
            parseDateFromName(v.name).takeIf { it > 0 }?.let { kotlin.math.abs(v.takenMs - it) > 86_400_000L } == true
        }
        if (toFix > 0) RemoteLogger.i("VideoDateRepair: $toFix videos need date fix")
        val newRepaired = mutableSetOf<String>()

        for (v in videos) {
            if (v.name in repaired) continue
            val correctMs = parseDateFromName(v.name).takeIf { it > 0 } ?: continue
            // Only repair if the stored date is wrong by more than 24 hours
            if (kotlin.math.abs(v.takenMs - correctMs) < 86_400_000L) {
                newRepaired.add(v.name)   // already correct — mark done
                continue
            }
            val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)

            // Read compressed bytes before deleting
            val bytes = try {
                context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
            } catch (_: Exception) { null }
            if (bytes == null || bytes.isEmpty()) continue

            // Delete Camera-owned row (requires MANAGE_EXTERNAL_STORAGE)
            val deleted = try { context.contentResolver.delete(videoUri, null, null) > 0 } catch (_: Exception) { false }
            if (!deleted) { RemoteLogger.i("VideoDateRepair: delete failed for ${v.name}"); continue }

            // Re-insert as app-owned with correct date
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, v.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, v.relativePath)
                put(MediaStore.Video.Media.DATE_TAKEN, correctMs)
                if (v.dateAddedSec > 0)    put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
                if (v.dateModifiedSec > 0) put(MediaStore.Video.Media.DATE_MODIFIED, v.dateModifiedSec)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val newUri = try {
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } catch (_: Exception) { null } ?: continue

            try {
                context.contentResolver.openOutputStream(newUri)?.use { it.write(bytes) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(newUri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.SIZE, bytes.size.toLong())
                        put(MediaStore.Video.Media.DATE_TAKEN, correctMs)
                        if (v.dateAddedSec > 0)    put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
                        if (v.dateModifiedSec > 0) put(MediaStore.Video.Media.DATE_MODIFIED, v.dateModifiedSec)
                    }, null, null)
                    // Second update — Samsung resets dates during IS_PENDING transition.
                    context.contentResolver.update(newUri, ContentValues().apply {
                        put(MediaStore.Video.Media.DATE_TAKEN, correctMs)
                        if (v.dateAddedSec > 0)    put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
                        if (v.dateModifiedSec > 0) put(MediaStore.Video.Media.DATE_MODIFIED, v.dateModifiedSec)
                    }, null, null)
                }
                newRepaired.add(v.name)
                val fixedDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(correctMs))
                RemoteLogger.i("VideoDateRepair: fixed ${v.name} -> $fixedDate")
            } catch (e: Exception) {
                RemoteLogger.i("VideoDateRepair: error on ${v.name}: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { context.contentResolver.delete(newUri, null, null) }
            }
        }

        if (newRepaired.isNotEmpty()) {
            RemoteLogger.i("VideoDateRepair: done, fixed ${newRepaired.size} videos")
            repaired.addAll(newRepaired)
            // Also mark as compressed so the main loop doesn't re-transcode repaired videos.
            val compressedNames = prefs.getStringSet(KEY_COMPRESSED_NAMES, emptySet())!!.toMutableSet()
            compressedNames.addAll(newRepaired)
            prefs.edit()
                .putStringSet(KEY_VIDEO_DATES_REPAIRED, repaired)
                .putStringSet(KEY_COMPRESSED_NAMES, compressedNames)
                .apply()
        }
    }
    companion object {
        private const val OLD_AGE_MS           = 30L * 24 * 60 * 60 * 1000
        private const val KEY_COMPRESSED       = "compressed_video_ids"    // legacy: stores IDs
        private const val KEY_COMPRESSED_NAMES = "compressed_video_names"  // v316+: stores display names
        private const val KEY_RESTORE          = "poster_restore_map"
        private const val KEY_REPAIRED         = "poster_repaired_set"
        internal const val KEY_POSTER_NAMES    = "poster_names"
        private const val KEY_VIDEO_DATES_REPAIRED = "compressed_video_dates_repaired"
        private const val KEY_COMPRESS_VERSION  = "compress_version"
        private const val COMPRESS_VERSION      = 2  // bump → clears compressed_video_names so H.265 re-runs
    }
}
