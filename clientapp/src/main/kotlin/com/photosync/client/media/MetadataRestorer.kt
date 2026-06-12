package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.photosync.client.hub.HubFileEntry
import com.photosync.client.hub.HubFilesClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * User-initiated ("Restore metadata from hub"): MIGRATES every already-compressed photo on the
 * phone to the current spec by re-downloading its pristine ORIGINAL from the hub and re-compressing
 * it with the current converter, then storing it via MediaStoreHelper.replaceFile.
 *
 * Result per file: a real `.webp` (image/webp), full original metadata copied LOSSLESSLY (EXIF incl.
 * vendor MakerNotes, ICC profile, XMP), correct orientation, and DATE_ADDED = capture date so the
 * gallery date/order finally sticks on Samsung. Running it across the whole library also surfaces
 * any edge cases in the pipeline.
 *
 * Only WebP-format copies are touched; true original JPEGs on the phone keep their own EXIF.
 * Skips files whose original isn't on the hub, or where re-compression yields no size benefit.
 */
class MetadataRestorer(private val context: Context) {

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    fun restore(
        ip: String, port: Int, deviceName: String,
        hubFiles: List<HubFileEntry>,
        progress: ((done: Int, total: Int, name: String) -> Unit)? = null
    ): Int {
        // Map every image ORIGINAL on the hub by lowercase STEM, so a phone "foo.webp" (already
        // migrated) still resolves to its hub original "foo.jpg" and can be re-processed. Prefer a
        // non-.webp original over a .webp if both somehow exist for a stem.
        val hubByStem = HashMap<String, String>()
        for (f in hubFiles) {
            if (f.deviceName != deviceName) continue
            if (f.displayName.substringAfterLast('.', "").lowercase() !in imageExts) continue
            val stem = f.displayName.substringBeforeLast('.').lowercase()
            val cur = hubByStem[stem]
            if (cur == null || (cur.endsWith(".webp", true) && !f.displayName.endsWith(".webp", true)))
                hubByStem[stem] = f.displayName
        }
        if (hubByStem.isEmpty()) return 0

        // Phone images whose stem has a hub original — carry the HUB name so we fetch the right file.
        data class Local(val id: Long, val name: String, val hubName: String, val rel: String, val taken: Long)
        val targets = ArrayList<Local>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN),
            null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iRp = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iDt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val hubName = hubByStem[name.substringBeforeLast('.').lowercase()] ?: continue
                targets.add(Local(c.getLong(iId), name, hubName, c.getString(iRp) ?: "DCIM/Camera/", c.getLong(iDt)))
            }
        }
        if (targets.isEmpty()) return 0

        var done = 0
        var consecutiveHubFails = 0
        val store = MediaStoreHelper(context)
        targets.forEachIndexed { index, t ->
            try {
                progress?.invoke(index + 1, targets.size, t.name)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, t.id)
                val localBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
                // Only re-process COMPRESSED copies (WebP, incl. the legacy WebP-in-.jpg files).
                // True original JPEGs on the phone still carry their own metadata — leave them.
                if (!isWebp(localBytes)) return@forEachIndexed

                // Pull the PRISTINE original from the hub and re-compress it with the current
                // converter, then store it via replaceFile. This migrates every old compressed photo
                // to the new spec in one pass: a real .webp file, full metadata copied losslessly from
                // the original (EXIF incl. MakerNotes, ICC, XMP), and DATE_ADDED = capture date so the
                // gallery date/orientation finally stick. Running it over the whole library also
                // surfaces any edge cases in the new pipeline.
                val original = HubFilesClient.fetchFile(ip, port, deviceName, t.hubName)
                if (original == null) {
                    // Hub unreachable — bail out fast rather than crawl through timeouts per file.
                    if (++consecutiveHubFails >= 8) throw IllegalStateException("hub unreachable")
                    return@forEachIndexed
                }
                consecutiveHubFails = 0
                if (original.isEmpty() || original.size > MAX_ORIGINAL_BYTES) return@forEachIndexed

                val webp = WebPConverter.convert(original, context.cacheDir) ?: return@forEachIndexed
                if (webp.size >= original.size) return@forEachIndexed   // no size benefit — skip

                // replaceFile renames to .webp + image/webp, app-owned, and reads the capture date
                // straight out of the EXIF the converter just copied in (DATE_TAKEN + DATE_ADDED).
                store.replaceFile(t.id, "image/webp", webp, t.taken)
                done++
                // Throttle so MediaProvider / the gallery stay responsive during the mass rewrite.
                if (done % 10 == 0) Thread.sleep(200)
            } catch (th: Throwable) {
                if (th is IllegalStateException && th.message == "hub unreachable") throw th   // stop the whole run
                /* otherwise skip this file */
            }
        }
        return done
    }

    private fun isWebp(b: ByteArray): Boolean =
        b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
        b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
        b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    companion object {
        // Skip absurdly large originals — the re-compress decodes the whole image in memory.
        private const val MAX_ORIGINAL_BYTES = 50L * 1024 * 1024
    }
}
