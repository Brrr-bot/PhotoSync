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

        // LAN IP stays usable for 15 min between (sparse) hub handshakes — the old 90 s window was
        // almost always stale by the time the hourly job ran, forcing a fall-back to the flaky
        // Tailscale path. Every bail below now logs a reason so the live feed is never silent.
        val ip = ClientForegroundService.liveHubIp
            ?.takeIf { System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 900_000L }
            ?: ClientForegroundService.liveHubTailscaleIp
            ?: run { RemoteLogger.i("⏸ VideoSpace: hub not reachable (no recent IP) — skipping this cycle"); return Summary(0, 0, 0, 0) }
        val port = ClientForegroundService.liveHubPort

        val hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) }
            catch (e: Exception) { RemoteLogger.i("⏸ VideoSpace: hub fetch failed (${e.javaClass.simpleName}) — skipping this cycle"); return Summary(0, 0, 0, 0) }
        if (hubFiles.isEmpty()) { RemoteLogger.i("⏸ VideoSpace: hub returned 0 files — skipping this cycle"); return Summary(0, 0, 0, 0) }
        val hubByName = HashMap<String, HubInfo>()
        for (f in hubFiles) {
            val existing = hubByName[f.displayName]
            if (existing == null || f.sizeBytes > existing.size)
                hubByName[f.displayName] = HubInfo(f.deviceName, f.sizeBytes, f.lastModifiedMs)
        }

        repairPosterDates(hubByName)

        // Legacy ID-based tracking (pre-v316) — still respected to avoid re-compressing.
        val compressedIds = prefs.getStringSet(KEY_COMPRESSED, emptySet())!!
        // Name-based tracking (v316+). If COMPRESS_VERSION has bumped, clear the set so all
        // videos get re-evaluated at the new quality settings (H.265 720p/4 Mbps).
        val storedVersion = prefs.getInt(KEY_COMPRESS_VERSION, 0)
        if (storedVersion < COMPRESS_VERSION) {
            prefs.edit()
                .remove(KEY_COMPRESSED_NAMES)
                .remove(KEY_COMPRESSED)
                .putInt(KEY_COMPRESS_VERSION, COMPRESS_VERSION)
                .apply()
            RemoteLogger.i("VideoSpace: quality upgrade to v$COMPRESS_VERSION — re-transcoding all videos")
        }
        val compressedNames  = prefs.getStringSet(KEY_COMPRESSED_NAMES, emptySet())!!.toMutableSet()
        // Restored/downloaded videos: name -> restore time (ms). They are kept as the full original
        // for a 24 h grace period, then become eligible for HEVC compression on a later run. They are
        // NEVER posterised (the user pulled them back on purpose — posterising would loop).
        val userRestoredAt = HashMap<String, Long>()
        prefs.getStringSet(KEY_USER_RESTORED, emptySet())!!.forEach { e ->
            val i = e.lastIndexOf('|')
            if (i > 0) userRestoredAt[e.substring(0, i)] = e.substring(i + 1).toLongOrNull() ?: 0L
            else userRestoredAt[e] = 0L   // legacy bare name → eligible immediately
        }
        val restoredToClear = mutableSetOf<String>()

        val videos = queryVideos()
        var thumbed = 0; var compressed = 0; var skipped = 0; var freed = 0L
        // Skip-reason tallies → one readable summary at the end instead of a line per skipped file.
        var skNotOnHub = 0; var skGrace = 0; var skAlreadyHevc = 0; var skNoDate = 0; var skNoShrink = 0; var skOther = 0
        val total = videos.size
        if (total > 0) RemoteLogger.i("🎬 VideoSpace: scanning $total video(s) (transcode H.264→HEVC / posterise old)…")

        videos.forEachIndexed { index, v ->
            try {
                progress?.invoke(index + 1, total, v.name)

                val hubEntry = hubByName[v.name]
                if (hubEntry == null || hubEntry.size < v.size) { skipped++; skNotOnHub++; return@forEachIndexed }

                // Determine age from filename first — more reliable than DATE_TAKEN which
                // Samsung zeros out during IS_PENDING transitions.
                val now = System.currentTimeMillis()
                val captureMs = parseDateFromName(v.name).takeIf { it > 0 && it <= now }
                    ?: v.takenMs.takeIf { it > 0 && it <= now && it != v.dateAddedSec * 1000L }
                    ?: (v.dateAddedSec * 1000L).takeIf { it > 0 && it <= now }
                    ?: now
                val ageMs = now - captureMs
                val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)

                // Restored/downloaded video: keep the full original through a 24 h grace, then it is
                // eligible to be transcoded to HEVC (never posterised).
                val restoredAt = userRestoredAt[v.name]
                val restoredEligible = restoredAt != null && (now - restoredAt) >= RESTORE_GRACE_MS
                if (restoredAt != null && !restoredEligible) { skipped++; skGrace++; return@forEachIndexed }

                if (!restoredEligible && ageMs > OLD_AGE_MS) {
                    // Old video -> replace with a JPEG poster so the gallery keeps a thumbnail.
                    // DATE FIX: never fall back to dateAddedSec as poster date (that causes all
                    // posters to appear on the day the video was added, not when it was captured).
                    // Fallback chain: filename > internal metadata > hub lastModified > skip.
                    val now2 = System.currentTimeMillis()
                    val takenMs = parseDateFromName(v.name).takeIf { it > 0 && it <= now2 }
                        ?: readVideoDate(videoUri)?.takeIf { it > 0 && it <= now2 }
                        ?: hubEntry.lastModifiedMs.takeIf { it > 0 && it <= now2 }
                        ?: v.takenMs.takeIf { it > 0 && it <= now2 && it != v.dateAddedSec * 1000L }
                        ?: 0L
                    if (takenMs == 0L) { skipped++; skNoDate++; return@forEachIndexed }   // no reliable date — leave for next cycle

                    val poster = VideoThumbnailer.makePosterJpeg(context, videoUri) ?: run { skipped++; skOther++; return@forEachIndexed }
                    val posterName = v.name.substringBeforeLast('.') + ".jpg"
                    val stampedPoster = VideoThumbnailer.stampPosterExif(poster, takenMs)
                    insertPoster(v, stampedPoster, posterName, takenMs) ?: run { skipped++; skOther++; return@forEachIndexed }
                    rememberRestore(posterName, hubEntry.device, v.name)
                    val deleted = try { context.contentResolver.delete(videoUri, null, null) > 0 } catch (_: Exception) { false }
                    if (deleted) {
                        thumbed++; freed += (v.size - stampedPoster.size).coerceAtLeast(0L)
                        RemoteLogger.i("🖼 ${v.name}  old video → poster JPEG (${dateStr(takenMs)}, freed ${mb(v.size)})")
                    } else { skipped++; skOther++ }
                } else if (isHevc(videoUri)) {
                    // Already HEVC — nothing to do. Decided by the ACTUAL codec in the file, not a
                    // name list, so a video can never get stuck "marked done" while still H.264.
                    if (restoredEligible) restoredToClear.add(v.name)
                    skipped++; skAlreadyHevc++
                } else if (restoredEligible || v.name !in compressedNames) {
                    // Recent H.264 video, OR a restored original past its 24 h grace -> transcode to
                    // HEVC, preserving filename + original date.
                    RemoteLogger.i("🎞 ${v.name}  H.264 ${mb(v.size)} → transcoding to HEVC…")
                    val tmp = File(context.cacheDir, "vtrans_${v.id}.mp4")
                    var shrank = false
                    try {
                        val okT = VideoTranscoder.transcode(context, videoUri, tmp.absolutePath)
                        if (okT && tmp.exists() && tmp.length() in 1 until (v.size * 9 / 10)) {
                            val bytes = tmp.readBytes()
                            val savedBytes = v.size - bytes.size
                            if (replaceCompressedVideo(v, bytes)) {
                                compressed++; freed += savedBytes.coerceAtLeast(0L); shrank = true
                                val pct = 100 - (bytes.size * 100L / v.size.coerceAtLeast(1L))
                                RemoteLogger.i("✅ ${v.name}  HEVC ${mb(v.size)}→${mb(bytes.size.toLong())} (−$pct%)")
                            }
                        }
                    } finally { tmp.delete() }
                    // Only remember files we genuinely COULDN'T shrink, so we don't retry them every
                    // cycle. A successful transcode is now HEVC and is recognised by the codec check
                    // above — no name tracking needed, so a future restored original re-compresses.
                    if (!shrank) {
                        compressedNames.add(v.name); skNoShrink++
                        RemoteLogger.i("⏭ ${v.name}  HEVC gave no size benefit — keeping original")
                    } else if (restoredEligible) restoredToClear.add(v.name)
                }
            } catch (_: Throwable) { skipped++; skOther++ }
        }

        prefs.edit().putStringSet(KEY_COMPRESSED_NAMES, compressedNames).apply()
        if (restoredToClear.isNotEmpty()) {
            val cur = prefs.getStringSet(KEY_USER_RESTORED, emptySet())!!
            val kept = cur.filterNot { e ->
                val name = e.substringBefore('|')
                name in restoredToClear
            }.toSet()
            prefs.edit().putStringSet(KEY_USER_RESTORED, kept).apply()
        }
        if (compressed > 0 || thumbed > 0 || skNoShrink > 0)
            RemoteLogger.i("✓ VideoSpace done — $compressed transcoded, $thumbed posterised (${freed / 1_048_576}MB freed) · " +
                "skipped $skipped [${skAlreadyHevc} already-HEVC, $skNotOnHub not-on-hub, $skGrace in-grace, $skNoShrink no-gain, $skNoDate no-date, $skOther other]")
        return Summary(thumbed, compressed, skipped, freed)
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1_048_576) "%.1fMB".format(bytes / 1_048_576.0) else "${bytes / 1024}KB"

    private fun dateStr(ms: Long): String =
        if (ms <= 0) "no date" else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))

    // ---- MediaStore helpers ---------------------------------------------------

    private data class VideoRow(
        val id: Long, val name: String, val size: Long, val takenMs: Long, val relativePath: String,
        val dateAddedSec: Long, val dateModifiedSec: Long, val dataPath: String? = null
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
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATA
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
            val iDataCol = c.getColumnIndex(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val da = c.getLong(iDa)
                val takenMs = c.getLong(iDt).takeIf { it > 0 } ?: (da * 1000L)
                val data = if (iDataCol >= 0) c.getString(iDataCol) else null
                out.add(VideoRow(c.getLong(iId), name, c.getLong(iSz), takenMs,
                    c.getString(iRp) ?: "DCIM/", da, c.getLong(iDm), data))
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
            // CRITICAL: the transcoder stamps "now" into the new MP4's internal creation_time
            // (mvhd/tkhd/mdhd). Samsung re-reads that on every scan and overrides DATE_TAKEN, so the
            // compressed video would appear under TODAY instead of its real shoot date. Patch the
            // atoms to takenMs in place — same data-retention step used everywhere we write a video.
            if (takenMs > 0) {
                val patched = try { Mp4DateEditor.setCreationTime(context, newUri, takenMs) } catch (_: Throwable) { false }
                if (patched) {
                    val sec = takenMs / 1000L
                    runCatching {
                        context.contentResolver.update(newUri, ContentValues().apply {
                            put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
                            if (v.dateAddedSec > 0) put(MediaStore.Video.Media.DATE_ADDED, v.dateAddedSec)
                            put(MediaStore.Video.Media.DATE_MODIFIED, sec)
                        }, null, null)
                    }
                    val path = try {
                        context.contentResolver.query(newUri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    } catch (_: Exception) { null }
                    if (path != null) runCatching {
                        // CRITICAL: set the file's mtime to the capture time. The transcoder writes the
                        // file "now", and Samsung's media scanner falls back to the file mtime for
                        // DATE_TAKEN when it can't read the mvhd — which would put the clip under the
                        // processing time (sorting it as the newest item). Making mtime == capture time
                        // means every source (mvhd atom, file mtime, MediaStore) agrees to the second.
                        java.io.File(path).setLastModified(takenMs)
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
                    }
                }
                RemoteLogger.i("VideoSpace: compressed ${v.name} date-atoms patched=$patched -> $takenMs")
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
    /** Best-effort date from a media filename. Prefers full timestamp (YYYYMMDD_HHMMSS),
     *  falls back to date-only (midnight), then a 13-digit epoch-ms substring. */
    private fun parseDateFromName(name: String): Long {
        val stem = name.substringBeforeLast('.')
        // Full timestamp: 20260504_141510 / 20260504-141510 → 2026-05-04 14:15:10
        Regex("(20\\d{2})(\\d{2})(\\d{2})[_\\-](\\d{2})(\\d{2})(\\d{2})").find(stem)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            try {
                return java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(),
                        h.toInt(), mi.toInt(), s.toInt())
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        // Date only → midnight
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(stem)?.let { m ->
            val (y, mo, d) = m.destructured
            try {
                return java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        stem.toLongOrNull()?.let {
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
        val videos = queryVideos()
        RemoteLogger.i("VideoDateRepair: scanning ${videos.size} videos")
        // The wrong date is baked into the MP4's internal creation_time (mvhd/tkhd/mdhd) by an
        // old transcode. Samsung reads that and overrides DATE_TAKEN, so a MediaStore-only fix
        // reverts. We edit the atoms in place (fast, local, no network) then sync MediaStore +
        // rescan. Self-terminating: once the internal date is correct, the >24h test is false.
        // 2-minute tolerance: a camera filename timestamp (YYYYMMDD_HHMMSS) IS the capture second,
        // so a correctly-dated clip matches to within seconds. A transcode that lands the clip on its
        // processing time is typically only tens of minutes off (e.g. a 10:17 clip stamped 10:48),
        // which a loose 2h window silently let through — that made compressed clips sort under the
        // wrong minute. We want the date correct to the second, so anything off by >2 min is wrong.
        // Camera/screenshot only: DOWNLOAD-folder videos keep their download date (never the embedded
        // filename date), matching the image rule.
        val tolMs = 2 * 60 * 1000L
        val toFix = videos.count { v ->
            val correct = parseDateFromName(v.name)
            !isDownloadFolder(v.relativePath) && correct > 0 && kotlin.math.abs(v.takenMs - correct) > tolMs
        }
        if (toFix > 0) RemoteLogger.i("VideoDateRepair: $toFix videos need date fix")
        val newRepaired = mutableSetOf<String>()

        for (v in videos) {
            if (isDownloadFolder(v.relativePath)) continue   // downloaded clips keep their download date
            val correctMs = parseDateFromName(v.name).takeIf { it > 0 } ?: continue
            if (kotlin.math.abs(v.takenMs - correctMs) < tolMs) continue
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id)

            // 1. Patch the MP4's internal creation_time/modification_time atoms (via the URI's fd).
            val patched = try { Mp4DateEditor.setCreationTime(context, uri, correctMs) } catch (_: Throwable) { false }
            if (!patched) { RemoteLogger.i("VideoDateRepair: atom patch failed for ${v.name}"); continue }

            // 2. Sync MediaStore date so the gallery updates immediately.
            val correctSec = correctMs / 1000L
            val path = v.dataPath
            try {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_TAKEN, correctMs)
                    put(MediaStore.Video.Media.DATE_ADDED, if (v.dateAddedSec > 0) v.dateAddedSec else correctSec)
                    put(MediaStore.Video.Media.DATE_MODIFIED, correctSec)
                }, null, null)
            } catch (_: Exception) {}

            // 3. Set the file mtime to the capture time, THEN rescan. Samsung's scanner falls back to
            //    the file mtime for DATE_TAKEN when it can't read the mvhd, so mtime must agree or the
            //    rescan would re-stamp the clip with its (later) write time and the fix wouldn't stick.
            if (path != null) try {
                java.io.File(path).setLastModified(correctMs)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
            } catch (_: Exception) {}

            newRepaired.add(v.name)
            val fixedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(correctMs))
            RemoteLogger.i("VideoDateRepair: fixed ${v.name} -> $fixedDate")
        }

        if (newRepaired.isNotEmpty()) {
            RemoteLogger.i("VideoDateRepair: done, fixed ${newRepaired.size} videos")
            val compressedNames = prefs.getStringSet(KEY_COMPRESSED_NAMES, emptySet())!!.toMutableSet()
            compressedNames.addAll(newRepaired)
            prefs.edit().putStringSet(KEY_COMPRESSED_NAMES, compressedNames).apply()
        }
    }

    /** Authoritative capture date for a video: filename timestamp → DATE_ADDED (sec→ms). */
    private fun videoCorrectDate(v: VideoRow): Long {
        parseDateFromName(v.name).takeIf { it > 0 }?.let { return it }
        return if (v.dateAddedSec > 0) v.dateAddedSec * 1000L else 0L
    }

    /** True if a video lives in a chat/social/download app folder — those keep their download date,
     *  never the original capture date embedded in the filename (matches the image rule). */
    private fun isDownloadFolder(relativePath: String): Boolean {
        val p = relativePath.lowercase()
        return DOWNLOAD_FOLDER_HINTS.any { p.contains(it) }
    }

    /**
     * True if the video's actual video track is already HEVC (H.265). Decided from the container's
     * track format, so the "is it compressed?" question is answered by real content — not a name
     * list that can drift out of sync (the bug that left H.264 files marked "done").
     * On any read failure returns false (treat as not-yet-HEVC and let transcode decide).
     */
    private fun isHevc(uri: Uri): Boolean = try {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var hevc = false
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hevc = mime.equals("video/hevc", true) || mime.contains("hevc", true)
                    break
                }
            }
            hevc
        } finally { ex.release() }
    } catch (_: Throwable) { false }

    companion object {
        private val DOWNLOAD_FOLDER_HINTS = listOf(
            "whatsapp", "zalo", "telegram", "messenger", "facebook", "instagram", "viber",
            "line", "wechat", "kakaotalk", "signal", "snapchat", "tiktok", "twitter", "download"
        )
        private const val OLD_AGE_MS           = 30L * 24 * 60 * 60 * 1000
        private const val RESTORE_GRACE_MS     = 24L * 60 * 60 * 1000  // keep a restored original 24 h before compressing
        private const val KEY_COMPRESSED       = "compressed_video_ids"    // legacy: stores IDs
        private const val KEY_COMPRESSED_NAMES = "compressed_video_names"  // v316+: stores display names
        internal const val KEY_RESTORE         = "poster_restore_map"
        private const val KEY_REPAIRED         = "poster_repaired_set"
        internal const val KEY_POSTER_NAMES    = "poster_names"
        /** Videos the user explicitly restored from hub — never auto-posterize these again. */
        internal const val KEY_USER_RESTORED   = "user_restored_videos"
        private const val KEY_VIDEO_DATES_REPAIRED = "compressed_video_dates_repaired"
        private const val KEY_COMPRESS_VERSION  = "compress_version"
        private const val COMPRESS_VERSION      = 3  // bump → clears compressed_video_names so H.265 re-runs
    }
}
