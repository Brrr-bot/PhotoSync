package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
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

        // Build a lookup of what the hub holds: displayName -> (deviceName, sizeBytes)
        val hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) } catch (_: Exception) { emptyList() }
        if (hubFiles.isEmpty()) return Summary(0, 0, 0, 0)
        val hubByName = HashMap<String, Pair<String, Long>>()
        for (f in hubFiles) {
            val existing = hubByName[f.displayName]
            if (existing == null || f.sizeBytes > existing.second) hubByName[f.displayName] = f.deviceName to f.sizeBytes
        }

        val compressedIds = prefs.getStringSet(KEY_COMPRESSED, emptySet())!!.toMutableSet()
        val videos = queryVideos()
        var thumbed = 0; var compressed = 0; var skipped = 0; var freed = 0L
        val total = videos.size

        videos.forEachIndexed { index, v ->
            try {
                progress?.invoke(index + 1, total, v.name)

                // SAFETY: only act on videos the hub definitely has the full copy of.
                val hubEntry = hubByName[v.name]
                if (hubEntry == null || hubEntry.second < v.size) { skipped++; return@forEachIndexed }

                val ageMs = System.currentTimeMillis() - v.takenMs
                val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)

                if (ageMs > OLD_AGE_MS) {
                    // ── Old video → poster image, delete the video ──
                    val poster = VideoThumbnailer.makePosterJpeg(context, videoUri) ?: run { skipped++; return@forEachIndexed }
                    val posterName = v.name.substringBeforeLast('.') + "_video.jpg"
                    val inserted = insertPoster(posterName, v.relativePath, v.takenMs, poster)
                    if (inserted == null) { skipped++; return@forEachIndexed }
                    // Remember how to get the original back (device + name on the hub).
                    rememberRestore(posterName, hubEntry.first, v.name)
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
        val id: Long, val name: String, val size: Long, val takenMs: Long, val relativePath: String
    )

    private fun queryVideos(): List<VideoRow> {
        val out = ArrayList<VideoRow>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_TAKEN,
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
            val iRp = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val takenMs = c.getLong(iDt).takeIf { it > 0 } ?: (c.getLong(iDa) * 1000L)
                out.add(VideoRow(c.getLong(iId), name, c.getLong(iSz), takenMs, c.getString(iRp) ?: "DCIM/"))
            }
        }
        return out
    }

    /** Inserts the poster JPEG as an image so it shows in the gallery where the video was. */
    private fun insertPoster(name: String, videoRelativePath: String, takenMs: Long, jpeg: ByteArray): Uri? {
        // Images may only go under DCIM/ or Pictures/.
        val rel = if (videoRelativePath.startsWith("DCIM", true) || videoRelativePath.startsWith("Pictures", true))
            videoRelativePath else "Pictures/"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, rel)
            if (takenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
        }
        return try {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
            uri
        } catch (_: Exception) { null }
    }

    private fun rememberRestore(posterName: String, device: String, videoName: String) {
        val set = prefs.getStringSet(KEY_RESTORE, emptySet())!!.toMutableSet()
        set.add("$posterName|$device|$videoName")
        prefs.edit().putStringSet(KEY_RESTORE, set).apply()
    }

    companion object {
        private const val OLD_AGE_MS = 30L * 24 * 60 * 60 * 1000   // 1 month
        private const val KEY_COMPRESSED = "compressed_video_ids"
        private const val KEY_RESTORE    = "poster_restore_map"
    }
}
