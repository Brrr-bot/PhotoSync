package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * LOCAL, no-network repair: resets the orientation of COMPRESSED (WebP) photos to NORMAL.
 *
 * Why: Samsung's decoder bakes EXIF rotation into upright pixels during WebP compression, so the
 * compressed copy is already upright and must be ORIENTATION_NORMAL. An earlier metadata pass wrongly
 * copied the original's sideways-sensor orientation onto these baked pixels, double-rotating them.
 *
 * This runs entirely on-device (no hub) so it can't hang on a dropped connection, and it throttles
 * the delete+reinsert churn so MediaProvider / the gallery stay responsive.
 */
class RotationFixer(private val context: Context) {

    fun fix(progress: ((done: Int, total: Int, name: String) -> Unit)? = null): Int {
        data class Row(val id: Long, val name: String, val rel: String, val taken: Long)
        val rows = ArrayList<Row>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.ORIENTATION),
            "${MediaStore.Images.Media.ORIENTATION} > 0", null, null   // only rotated rows are candidates
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iRp = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iDt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                rows.add(Row(c.getLong(iId), c.getString(iNm) ?: continue, c.getString(iRp) ?: "DCIM/Camera/", c.getLong(iDt)))
            }
        }
        if (rows.isEmpty()) return 0

        var done = 0
        val tmp = File(context.cacheDir, "rotfix_tmp")
        rows.forEachIndexed { index, r ->
            try {
                progress?.invoke(index + 1, rows.size, r.name)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, r.id)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
                if (!isWebp(bytes)) return@forEachIndexed   // only OUR compressed copies are baked-upright; leave real JPEGs

                // Already normal in the file? nothing to do.
                val curOri = try { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                             catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
                if (curOri == ExifInterface.ORIENTATION_NORMAL) {
                    // File is fine but MediaStore still shows a rotation — a light reinsert clears it.
                }

                val stamped = setOrientationNormal(bytes, tmp) ?: return@forEachIndexed
                val dateMs = r.taken
                val dateSec = if (dateMs > 0) dateMs / 1000L else 0L

                context.contentResolver.delete(uri, null, null)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, r.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, r.rel)
                    put(MediaStore.Images.Media.ORIENTATION, 0)   // pixels are upright — force normal
                    if (dateMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, dateMs)
                    if (dateSec > 0) { put(MediaStore.Images.Media.DATE_ADDED, dateSec); put(MediaStore.Images.Media.DATE_MODIFIED, dateSec) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val nu = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
                context.contentResolver.openOutputStream(nu)?.use { it.write(stamped) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(nu, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                        put(MediaStore.Images.Media.SIZE, stamped.size.toLong())
                        put(MediaStore.Images.Media.ORIENTATION, 0)
                        if (dateMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, dateMs)
                    }, null, null)
                    if (dateMs > 0) context.contentResolver.update(nu, ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_TAKEN, dateMs)
                        if (dateSec > 0) { put(MediaStore.Images.Media.DATE_ADDED, dateSec); put(MediaStore.Images.Media.DATE_MODIFIED, dateSec) }
                    }, null, null)
                }
                done++
                // Throttle so MediaProvider / the gallery keep up (mass reinserts overwhelm them).
                if (done % 15 == 0) Thread.sleep(250)
            } catch (_: Throwable) { /* skip */ }
            finally { tmp.delete() }
        }
        return done
    }

    private fun isWebp(b: ByteArray): Boolean =
        b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
        b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
        b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun setOrientationNormal(bytes: ByteArray, tmp: File): ByteArray? {
        return try {
            tmp.writeBytes(bytes)
            val exif = ExifInterface(tmp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
            tmp.readBytes()
        } catch (_: Exception) { null }
    }
}
