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
 * User-initiated: re-applies the FULL original metadata (orientation, GPS, all dates, camera/lens/
 * exposure, XMP, …) from the pristine hub backup onto the phone's COMPRESSED copies.
 *
 * Why: the WebP compression pass keeps EXIF dates but Samsung's gallery loses orientation/date on
 * the re-encoded `.jpg`-named WebP (MediaStore `orientation`/`datetaken` come back NULL → photos
 * show rotated and sort wrong). The hub holds the untouched original with every tag intact, so we
 * scrape it back and re-publish the local file so MediaStore honours it.
 *
 * Only WebP-format files are touched — those are the compressed copies that lost metadata; original
 * JPEGs on the phone still carry their own EXIF and are left alone.
 */
class MetadataRestorer(private val context: Context) {

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    fun restore(
        ip: String, port: Int, deviceName: String,
        hubFiles: List<HubFileEntry>,
        progress: ((done: Int, total: Int, name: String) -> Unit)? = null
    ): Int {
        val hubNames = hubFiles
            .filter { it.deviceName == deviceName && it.displayName.substringAfterLast('.', "").lowercase() in imageExts }
            .map { it.displayName }
            .toHashSet()
        if (hubNames.isEmpty()) return 0

        // Phone images that also exist on the hub (by exact name).
        data class Local(val id: Long, val name: String, val rel: String, val taken: Long)
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
                if (name in hubNames) targets.add(Local(c.getLong(iId), name, c.getString(iRp) ?: "DCIM/Camera/", c.getLong(iDt)))
            }
        }
        if (targets.isEmpty()) return 0

        var done = 0
        val tmp = File(context.cacheDir, "meta_restore_tmp")
        targets.forEachIndexed { index, t ->
            try {
                progress?.invoke(index + 1, targets.size, t.name)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, t.id)
                val localBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
                if (!isWebp(localBytes)) return@forEachIndexed   // only compressed copies lost metadata

                val meta = HubFilesClient.fetchMeta(ip, port, deviceName, t.name) ?: return@forEachIndexed
                if (meta.isEmpty()) return@forEachIndexed

                // Write all original tags into the local (compressed) bytes.
                val stamped = applyExif(localBytes, tmp, meta) ?: return@forEachIndexed

                // Capture date: original EXIF DateTimeOriginal > filename > existing MediaStore value.
                val dateMs = parseExifDate(meta[ExifInterface.TAG_DATETIME_ORIGINAL] ?: meta[ExifInterface.TAG_DATETIME])
                    .takeIf { it > 0 } ?: parseNameDate(t.name).takeIf { it > 0 } ?: t.taken
                val dateSec = if (dateMs > 0) dateMs / 1000L else 0L

                // Delete + re-insert so MediaStore re-reads the EXIF (orientation/date) on publish —
                // an in-place overwrite leaves orientation/datetaken NULL on Samsung 13.
                contentResolver().delete(uri, null, null)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, t.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")   // keep .jpg name + JPEG MIME (bytes are WebP; viewers read magic)
                    put(MediaStore.Images.Media.RELATIVE_PATH, t.rel)
                    if (dateMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(MediaStore.Images.Media.DATE_ADDED, dateSec); put(MediaStore.Images.Media.DATE_MODIFIED, dateSec) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val nu = contentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
                contentResolver().openOutputStream(nu)?.use { it.write(stamped) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver().update(nu, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                        put(MediaStore.Images.Media.SIZE, stamped.size.toLong())
                        if (dateMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, dateMs)
                    }, null, null)
                    if (dateMs > 0) contentResolver().update(nu, ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_TAKEN, dateMs)
                        if (dateSec > 0) { put(MediaStore.Images.Media.DATE_ADDED, dateSec); put(MediaStore.Images.Media.DATE_MODIFIED, dateSec) }
                    }, null, null)
                }
                done++
            } catch (_: Throwable) { /* skip this file */ }
            finally { tmp.delete() }
        }
        return done
    }

    private fun contentResolver() = context.contentResolver

    private fun isWebp(b: ByteArray): Boolean =
        b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
        b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
        b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    /** Writes every [meta] tag into [bytes] via ExifInterface (supports JPEG + WebP on API 31+). */
    private fun applyExif(bytes: ByteArray, tmp: File, meta: Map<String, String>): ByteArray? {
        return try {
            tmp.writeBytes(bytes)
            val exif = ExifInterface(tmp.absolutePath)
            for ((tag, value) in meta) {
                try { exif.setAttribute(tag, value) } catch (_: Exception) {}
            }
            exif.saveAttributes()
            tmp.readBytes()
        } catch (_: Exception) { null }
    }

    private fun parseExifDate(raw: String?): Long {
        if (raw.isNullOrBlank() || raw.startsWith("0000")) return 0L
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }.parse(raw.trim())?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun parseNameDate(name: String): Long {
        val stem = name.substringBeforeLast('.')
        val now = System.currentTimeMillis(); val since2000 = 946_684_800_000L
        Regex("""(\d{8})[_-](\d{6})""").find(stem)?.let { m ->
            runCatching {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }.parse(m.groupValues[1] + m.groupValues[2])?.time
            }.getOrNull()?.let { if (it in since2000..now) return it }
        }
        Regex("""(\d{13})""").find(stem)?.let { m -> m.groupValues[1].toLongOrNull()?.let { if (it in since2000..now) return it } }
        return 0L
    }
}
