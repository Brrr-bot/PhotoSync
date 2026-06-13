package com.photosync.client.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import com.photosync.client.util.RemoteLogger

/**
 * Compresses phone images to WebP after the hub confirms they are backed up on USB.
 *
 * This is the single compression step for images. The hub backs up originals to USB
 * (no compression there), then this class converts the phone's copy to WebP at 92% quality
 * with all EXIF preserved. Because this runs on the phone (Android 13), ExifInterface can
 * write EXIF to WebP — GPS, camera, dates, orientation all survive.
 *
 * Tracks compressed names in SharedPrefs so each image is only processed once.
 * Bump COMPRESS_VERSION to force a full re-run (e.g. after changing quality settings).
 */
class ImageSpaceManager(private val context: Context) {

    data class Summary(val compressed: Int, val skipped: Int, val freedBytes: Long)

    private val prefs = context.getSharedPreferences("image_space_state", Context.MODE_PRIVATE)

    fun process(progress: ((done: Int, total: Int, name: String) -> Unit)? = null): Summary {
        // LAN IP stays usable for 15 min between (sparse) hub handshakes — the old 90 s window was
        // almost always stale by the time the hourly job ran, forcing a fall-back to the flaky
        // Tailscale path. Every bail below now logs a reason so the live feed is never silent.
        val ip = ClientForegroundService.liveHubIp
            ?.takeIf { System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 900_000L }
            ?: ClientForegroundService.liveHubTailscaleIp
            ?: run { RemoteLogger.i("⏸ ImageSpace: hub not reachable (no recent IP) — skipping this cycle"); return Summary(0, 0, 0) }
        val port = ClientForegroundService.liveHubPort

        // Wait for the hub to warm up: right after a hub restart it briefly returns 0 files while it
        // re-indexes USB. Retry for ~30 s before giving up, so we don't waste a whole cycle.
        var hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) }
            catch (e: Exception) { RemoteLogger.i("⏸ ImageSpace: hub fetch failed (${e.javaClass.simpleName}) — skipping this cycle"); return Summary(0, 0, 0) }
        var warmTries = 0
        while (hubFiles.isEmpty() && warmTries < 5) {
            warmTries++
            RemoteLogger.i("⏳ ImageSpace: hub not warm yet (0 files) — waiting (try $warmTries/5)")
            Thread.sleep(6_000)
            hubFiles = try { HubFilesClient.fetchFiles(ip, port, limit = 10_000) }
                catch (e: Exception) { RemoteLogger.i("⏸ ImageSpace: hub fetch failed (${e.javaClass.simpleName}) — skipping this cycle"); return Summary(0, 0, 0) }
        }
        if (hubFiles.isEmpty()) { RemoteLogger.i("⏸ ImageSpace: hub returned 0 files after warm-up wait — skipping this cycle"); return Summary(0, 0, 0) }

        // Set of display names confirmed backed up on hub USB
        val hubNames = hubFiles.map { it.displayName }.toHashSet()

        // One-time clear of stale tracking when the logic version bumps. A "Restore from hub" or
        // download re-introduces the full ORIGINAL of a file previously marked compressed, which then
        // sticks as a large .jpg forever (ImageSpace skipped it by name). Clearing lets those
        // originals be re-compressed.
        if (prefs.getInt(KEY_COMPRESS_VERSION, 0) < COMPRESS_VERSION) {
            prefs.edit().remove(KEY_COMPRESSED).putInt(KEY_COMPRESS_VERSION, COMPRESS_VERSION).apply()
            RemoteLogger.i("ImageSpace: cleared stale compressed-tracking for v$COMPRESS_VERSION")
        }
        val compressedNames = prefs.getStringSet(KEY_COMPRESSED, emptySet())!!.toMutableSet()

        // Query the Images table DIRECTLY (not getMediaSince, which pre-filters out anything in the
        // various "already compressed" tracking sets — exactly the restored originals we need to
        // re-process). Candidates = anything backed up on the hub whose MIME isn't already WebP. The
        // real "is it already compressed?" decision is made per-file from its MAGIC BYTES below, not
        // from size or a name list. compressedNames only holds files we TRIED but couldn't shrink.
        data class Img(val id: Long, val displayName: String, val dateTaken: Long)
        val phoneImages = ArrayList<Img>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN),
            null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMt = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iDt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val mime = c.getString(iMt) ?: ""
                if (!mime.startsWith("image/") || mime == "image/webp") continue
                if (name !in hubNames || name in compressedNames) continue
                phoneImages.add(Img(c.getLong(iId), name, c.getLong(iDt)))
            }
        }

        var compressed = 0
        var skipped = 0
        var freedBytes = 0L
        // Skip-reason tallies so the run ends with one readable summary instead of a line per file.
        var skAlreadyWebp = 0; var skNoGain = 0; var skUnreadable = 0
        val total = phoneImages.size
        if (total > 0) RemoteLogger.i("🖼 ImageSpace: scanning $total backed-up image(s) for compression…")

        for ((index, image) in phoneImages.withIndex()) {
            progress?.invoke(index + 1, total, image.displayName)
            try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image.id)

                // Cheap content check: read just the header. If it's ALREADY WebP (a legacy
                // WebP-bytes-in-.jpg copy), it's compressed — leave it (don't re-decode the whole
                // file). Only a real JPEG/PNG original is read in full and compressed.
                val header = context.contentResolver.openInputStream(uri)?.use { ins ->
                    val h = ByteArray(12); val n = ins.read(h); if (n == 12) h else null
                } ?: run { skipped++; skUnreadable++; continue }
                if (isWebp(header)) { skipped++; skAlreadyWebp++; continue }

                val originalBytes = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() } ?: run { skipped++; skUnreadable++; continue }

                val webpBytes = WebPConverter.convert(originalBytes, context.cacheDir)
                if (webpBytes == null || webpBytes.size >= originalBytes.size) {
                    // Can't convert (old Android) or no size benefit — mark done, skip
                    compressedNames.add(image.displayName)
                    skipped++; skNoGain++
                    continue
                }

                val store = MediaStoreHelper(context)
                store.replaceFile(image.id, "image/webp", webpBytes, image.dateTaken)
                freedBytes += (originalBytes.size - webpBytes.size).toLong()
                // Do NOT add to compressedNames on success — the file is now image/webp and excluded
                // by MIME next cycle. Leaving its name out means that if a restore later brings the
                // original back, it gets re-compressed instead of being stuck.
                compressed++
                // ACTION line — one per file actually compressed, with before/after + date kept.
                val pct = 100 - (webpBytes.size * 100L / originalBytes.size.coerceAtLeast(1))
                RemoteLogger.i("🗜 ${image.displayName}  ${kb(originalBytes.size)}→${kb(webpBytes.size)} WebP (−$pct%, ${dateStr(image.dateTaken)})")
            } catch (t: Throwable) {
                RemoteLogger.i("⚠ ImageSpace ${image.displayName}: ${t.javaClass.simpleName}: ${t.message}")
                skipped++; skUnreadable++
            }
        }

        prefs.edit().putStringSet(KEY_COMPRESSED, compressedNames).apply()
        if (compressed > 0 || skNoGain > 0)
            RemoteLogger.i("✓ ImageSpace done — $compressed compressed (${freedBytes / 1_048_576}MB freed) · " +
                "skipped $skipped [${skAlreadyWebp} already-WebP, $skNoGain no-gain, $skUnreadable unreadable]")
        return Summary(compressed, skipped, freedBytes)
    }

    private fun kb(bytes: Int): String =
        if (bytes >= 1_048_576) "%.1fMB".format(bytes / 1_048_576.0) else "${bytes / 1024}KB"

    private fun dateStr(ms: Long): String =
        if (ms <= 0) "no date" else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))

    /** True if [b] starts with the RIFF????WEBP magic — i.e. the bytes are already a WebP image. */
    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() &&
        b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    companion object {
        private const val KEY_COMPRESSED = "compressed_image_names"
        // Bump to force a full re-run on all devices (clears stale compressed-tracking).
        const val COMPRESS_VERSION = 2
        const val KEY_COMPRESS_VERSION = "image_compress_version"
    }
}
