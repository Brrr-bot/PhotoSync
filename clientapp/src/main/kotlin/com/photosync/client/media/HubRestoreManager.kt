package com.photosync.client.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.photosync.client.hub.HubFileEntry
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.util.RemoteLogger
import java.io.File

/**
 * One-time restore: downloads every file the hub holds for this device that is not
 * already present in the phone's MediaStore.
 *
 * After the restore VideoSpaceManager handles old videos (poster + delete) and
 * ImageSpaceManager handles photos (compress to WebP) on their next normal cycle.
 *
 * Space estimate is always logged first so you can see the numbers before files land.
 */
class HubRestoreManager(private val context: Context) {

    data class Estimate(
        val imageCount: Int,
        val videoRecentCount: Int,
        val videoOldCount: Int,
        /** Estimated bytes on-phone after compression (WebP + H.265 + small posters). */
        val estimatedBytes: Long,
        /** Raw total bytes if uncompressed — for context. */
        val rawBytes: Long
    )

    private val prefs = context.getSharedPreferences("hub_restore_state", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RESTORE_DONE    = "restore_done_v1"
        private const val IMAGE_COMPRESS_RATIO = 0.40   // WebP ≈ 40 % of original JPEG
        private const val VIDEO_COMPRESS_RATIO = 0.60   // H.265 ≈ 60 % of original MP4
        private const val POSTER_SIZE_BYTES    = 80_000L // ~80 KB per poster JPEG
        private const val OLD_AGE_MS           = 30L * 24 * 60 * 60 * 1000L

        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        private val VIDEO_EXTS = setOf("mp4", "mov", "mkv", "avi", "3gp", "webm")
    }

    fun shouldRun(): Boolean = !prefs.getBoolean(KEY_RESTORE_DONE, false)
    fun markDone() = prefs.edit().putBoolean(KEY_RESTORE_DONE, true).apply()

    /**
     * Estimates on-phone space if every hub file is restored and compressed.
     * Uses hub [lastModifiedMs] as a proxy for capture age — not perfect for files
     * backed up long after capture, but good enough for the estimate.
     */
    fun estimate(hubFiles: List<HubFileEntry>, deviceName: String): Estimate {
        val myFiles = hubFiles.filter { it.deviceName == deviceName }
        val now = System.currentTimeMillis()

        var imageCount = 0;     var imageBytes = 0L
        var recentVideos = 0;   var recentVideoBytes = 0L
        var oldVideos = 0

        for (f in myFiles) {
            val ext = f.displayName.substringAfterLast('.', "").lowercase()
            val captureMs = parseDateFromName(f.displayName)
                .takeIf { it > 0 && it <= now }
                ?: f.lastModifiedMs.coerceAtMost(now)
            val ageMs = now - captureMs
            when {
                ext in IMAGE_EXTS -> { imageCount++; imageBytes += f.sizeBytes }
                ext in VIDEO_EXTS && ageMs <= OLD_AGE_MS -> { recentVideos++; recentVideoBytes += f.sizeBytes }
                ext in VIDEO_EXTS -> oldVideos++
            }
        }

        val rawBytes = imageBytes + recentVideoBytes
        val estimatedBytes =
            (imageBytes * IMAGE_COMPRESS_RATIO).toLong() +
            (recentVideoBytes * VIDEO_COMPRESS_RATIO).toLong() +
            (oldVideos * POSTER_SIZE_BYTES)

        val totalFiles = imageCount + recentVideos + oldVideos
        RemoteLogger.i(
            "HubRestore estimate: $totalFiles files for $deviceName " +
            "(${imageCount} photos, ${recentVideos} recent-vids, ${oldVideos} old-vids→poster)" +
            " | raw ${rawBytes / 1_000_000}MB → compressed ~${estimatedBytes / 1_000_000}MB"
        )
        return Estimate(imageCount, recentVideos, oldVideos, estimatedBytes, rawBytes)
    }

    /**
     * Downloads all hub files for [deviceName] that are not already in MediaStore.
     * Files are inserted with dates from their filenames so VideoSpaceManager /
     * ImageSpaceManager can use them correctly on the next compression cycle.
     *
     * Returns the number of files successfully restored.
     */
    fun restoreAll(
        ip: String, port: Int, deviceName: String,
        hubFiles: List<HubFileEntry>,
        progress: ((done: Int, total: Int, name: String) -> Unit)? = null
    ): Int {
        val myFiles = hubFiles.filter { it.deviceName == deviceName }
        if (myFiles.isEmpty()) {
            RemoteLogger.i("HubRestore: no files on hub for $deviceName")
            return 0
        }

        val existing = buildExistingNamesSet()
        // Stems of every image on the phone. A hub video whose stem matches an existing image
        // is a video that VideoSpaceManager already posterised (mp4 deleted, "VID_x.jpg" poster
        // kept). Restoring such a video would just get re-posterised → re-restored forever, so we
        // treat the poster's presence as "already handled" and never re-download the video.
        val imageStems = buildExistingImageStems()
        val toDownload = myFiles.filter { f ->
            if (f.displayName in existing) return@filter false
            val ext = f.displayName.substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTS &&
                f.displayName.substringBeforeLast('.').lowercase() in imageStems) {
                return@filter false   // poster already on phone — video was intentionally slimmed
            }
            true
        }

        RemoteLogger.i(
            "HubRestore: hub=${myFiles.size} phone=${existing.size} toDownload=${toDownload.size}"
        )
        if (toDownload.isEmpty()) {
            RemoteLogger.i("HubRestore: phone already has everything, nothing to do")
            return 0
        }

        val tmp = File(context.cacheDir, "hub_restore_tmp")
        var done = 0

        for ((index, f) in toDownload.withIndex()) {
            try {
                progress?.invoke(index + 1, toDownload.size, f.displayName)

                val ok = HubFilesClient.fetchFileToFile(ip, port, deviceName, f.displayName, tmp)
                if (!ok || !tmp.exists() || tmp.length() == 0L) {
                    RemoteLogger.i("HubRestore: download failed for ${f.displayName}")
                    continue
                }

                val now = System.currentTimeMillis()
                val dateMs = parseDateFromName(f.displayName).takeIf { it > 0 && it <= now }
                    ?: f.lastModifiedMs.takeIf { it > 0 && it <= now }
                    ?: now

                val ext = f.displayName.substringAfterLast('.', "").lowercase()
                val inserted = when {
                    ext in IMAGE_EXTS -> insertFromFile(
                        tmp, f.displayName, dateMs,
                        mimeForImage(ext),
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        isVideo = false
                    )
                    ext in VIDEO_EXTS -> insertFromFile(
                        tmp, f.displayName, dateMs,
                        mimeForVideo(ext),
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        isVideo = true
                    )
                    else -> false
                }

                if (inserted) {
                    done++
                    if (done % 50 == 0) RemoteLogger.i("HubRestore: $done/${toDownload.size} done")
                }
            } catch (t: Throwable) {
                RemoteLogger.i("HubRestore: error on ${f.displayName} — ${t.message}")
            } finally {
                tmp.delete()
            }
        }

        RemoteLogger.i("HubRestore: complete — restored $done / ${toDownload.size} files")
        return done
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildExistingNamesSet(): Set<String> {
        val names = mutableSetOf<String>()
        for ((uri, col) in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI  to MediaStore.Video.Media.DISPLAY_NAME
        )) {
            context.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                val i = c.getColumnIndex(col)
                if (i < 0) return@use
                while (c.moveToNext()) { c.getString(i)?.let { names.add(it) } }
            }
        }
        return names
    }

    /** Lowercase filename stems (no extension) of every image currently in MediaStore. */
    private fun buildExistingImageStems(): Set<String> {
        val stems = mutableSetOf<String>()
        val col = MediaStore.Images.Media.DISPLAY_NAME
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(col), null, null, null
        )?.use { c ->
            val i = c.getColumnIndex(col)
            if (i < 0) return@use
            while (c.moveToNext()) {
                c.getString(i)?.let { stems.add(it.substringBeforeLast('.').lowercase()) }
            }
        }
        return stems
    }

    /**
     * Inserts [src] into MediaStore using streaming (no full-file ByteArray in heap).
     * Uses IS_PENDING=1 pattern + double-update so DATE_TAKEN sticks on Samsung Android 13+.
     */
    private fun insertFromFile(
        src: File,
        displayName: String,
        dateMs: Long,
        mime: String,
        collection: android.net.Uri,
        isVideo: Boolean
    ): Boolean {
        val dateSec = dateMs / 1000L
        val nameCol  = if (isVideo) MediaStore.Video.Media.DISPLAY_NAME  else MediaStore.Images.Media.DISPLAY_NAME
        val mimeCol  = if (isVideo) MediaStore.Video.Media.MIME_TYPE      else MediaStore.Images.Media.MIME_TYPE
        val pathCol  = if (isVideo) MediaStore.Video.Media.RELATIVE_PATH  else MediaStore.Images.Media.RELATIVE_PATH
        val takenCol = if (isVideo) MediaStore.Video.Media.DATE_TAKEN     else MediaStore.Images.Media.DATE_TAKEN
        val addedCol = if (isVideo) MediaStore.Video.Media.DATE_ADDED     else MediaStore.Images.Media.DATE_ADDED
        val modCol   = if (isVideo) MediaStore.Video.Media.DATE_MODIFIED  else MediaStore.Images.Media.DATE_MODIFIED
        val pendCol  = if (isVideo) MediaStore.Video.Media.IS_PENDING     else MediaStore.Images.Media.IS_PENDING

        val values = ContentValues().apply {
            put(nameCol,  displayName)
            put(mimeCol,  mime)
            put(pathCol,  "DCIM/Camera/")
            put(takenCol, dateMs)
            put(addedCol, dateSec)
            put(modCol,   dateSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(pendCol, 1)
        }

        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (_: Exception) { null } ?: return false

        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fix = ContentValues().apply {
                    put(pendCol,  0)
                    put(takenCol, dateMs)
                    put(addedCol, dateSec)
                    put(modCol,   dateSec)
                }
                context.contentResolver.update(uri, fix, null, null)
                // Samsung resets dates on IS_PENDING transition — force them again
                context.contentResolver.update(uri, ContentValues().apply {
                    put(takenCol, dateMs); put(addedCol, dateSec); put(modCol, dateSec)
                }, null, null)
            }
            true
        } catch (_: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            false
        }
    }

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
        return 0L
    }

    private fun mimeForImage(ext: String) = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "webp"        -> "image/webp"
        "heic", "heif"-> "image/heic"
        else          -> "image/jpeg"
    }

    private fun mimeForVideo(ext: String) = when (ext) {
        "mp4"  -> "video/mp4"
        "mov"  -> "video/quicktime"
        "mkv"  -> "video/x-matroska"
        "3gp"  -> "video/3gpp"
        else   -> "video/$ext"
    }
}
