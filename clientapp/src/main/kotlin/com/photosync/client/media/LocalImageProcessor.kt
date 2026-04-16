package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import com.photosync.shared.model.MediaFileInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scans the device's image library and locally fixes:
 *  1. EXIF orientation — rotates pixels so the image displays correctly without relying on EXIF
 *  2. Missing DATE_TAKEN — reads EXIF DateTimeOriginal and writes it to MediaStore
 *
 * Runs on startup and periodically so the phone doesn't depend on the hub for these corrections.
 * Uses the same INSERT+DELETE replacement strategy as [MediaStoreHelper.replaceFile] so the
 * compression-loop guard (`replaced_original_ids`) prevents double-processing.
 *
 * Quality: 95% JPEG — rotation only, no downscale. The hub will compress further if/when it syncs.
 */
class LocalImageProcessor(private val context: Context) {

    private val prefs = context.getSharedPreferences("local_fix_state", Context.MODE_PRIVATE)
    private val mediaStore = MediaStoreHelper(context)
    private val compressionPrefs = context.getSharedPreferences("compression_state", Context.MODE_PRIVATE)

    // IDs we've already scanned (orientation confirmed correct, or already replaced/fixed)
    private fun getCheckedIds(): Set<String> =
        prefs.getStringSet(KEY_CHECKED, emptySet())!!

    private fun markChecked(id: Long) {
        val set = getCheckedIds().toMutableSet()
        set.add(id.toString())
        // Batch writes — accumulate up to 50 then flush
        pendingChecked.add(id.toString())
        if (pendingChecked.size >= 50) flushChecked()
    }

    private val pendingChecked = mutableSetOf<String>()

    fun flushChecked() {
        if (pendingChecked.isEmpty()) return
        val set = getCheckedIds().toMutableSet()
        set.addAll(pendingChecked)
        prefs.edit().putStringSet(KEY_CHECKED, set).apply()
        pendingChecked.clear()
    }

    /**
     * Scans all images not yet checked, fixes orientation and/or missing DATE_TAKEN.
     * [onProgress] is called periodically — (done, total, statusMessage).
     * Returns count of images actually fixed (rotated or date-corrected).
     */
    fun processUnfixed(onProgress: ((done: Int, total: Int, msg: String) -> Unit)? = null): Int {
        val checkedIds  = getCheckedIds()
        val replacedIds = compressionPrefs.getStringSet("replaced_original_ids", emptySet())!!
        val newIds      = compressionPrefs.getStringSet("compressed_new_ids", emptySet())!!
        val skipIds     = checkedIds + replacedIds + newIds

        val images = queryAllImages().filter { it.id.toString() !in skipIds }
        val total  = images.size
        if (total == 0) return 0

        var done  = 0
        var fixed = 0

        for (image in images) {
            done++
            if (done % 25 == 0) onProgress?.invoke(done, total, "Scanning… $done/$total")

            val bytes = try {
                mediaStore.openFileById(image.id, false)?.use { it.readBytes() }
            } catch (_: Exception) { null }

            if (bytes == null || bytes.isEmpty()) {
                markChecked(image.id)
                continue
            }

            // Read EXIF — use ByteArrayInputStream (no temp file needed for reads)
            val exif = try {
                ExifInterface(ByteArrayInputStream(bytes))
            } catch (_: Exception) {
                markChecked(image.id)
                continue
            }

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            // Resolve the best DATE_TAKEN:
            //   1. MediaStore DATE_TAKEN (already correct)
            //   2. EXIF DateTimeOriginal
            //   3. Filename — e.g. IMG_20250804_122335.jpg (most reliable fallback)
            val effectiveDateTaken = when {
                image.dateTaken > 0 -> image.dateTaken
                else -> exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.let { parseExifDate(it) }
                    ?: parseDateFromFilename(image.displayName)
                    ?: 0L
            }

            val needsRotation = orientation != ExifInterface.ORIENTATION_NORMAL &&
                                orientation != ExifInterface.ORIENTATION_UNDEFINED &&
                                orientation != 0

            val needsDateFix = effectiveDateTaken > 0 && image.dateTaken == 0L

            when {
                needsRotation -> {
                    // Rotate pixels, clear orientation tag, stamp date, replace via MediaStore
                    val rotated = rotateAndEncode(bytes, orientation, effectiveDateTaken)
                    if (rotated != null) {
                        try {
                            mediaStore.replaceFile(image.id, image.mimeType.ifEmpty { "image/jpeg" },
                                rotated, effectiveDateTaken)
                            onProgress?.invoke(done, total, "Fixed orientation: ${image.displayName}")
                            fixed++
                            // replaceFile() adds to replaced_original_ids — no markChecked needed
                        } catch (_: Exception) {
                            markChecked(image.id)
                        }
                    } else {
                        markChecked(image.id)
                    }
                }

                needsDateFix -> {
                    // Orientation is fine — just update DATE_TAKEN in MediaStore
                    fixDateOnly(image.id, effectiveDateTaken)
                    onProgress?.invoke(done, total, "Fixed date: ${image.displayName}")
                    fixed++
                    markChecked(image.id)
                }

                else -> {
                    // Already correct — just record so we skip it next time
                    markChecked(image.id)
                }
            }
        }

        flushChecked()
        return fixed
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Rotates [bytes] to match [orientation], encodes at 95% JPEG quality.
     * Stamps [dateTakenMs] and clears EXIF orientation tag in the output file.
     */
    private fun rotateAndEncode(bytes: ByteArray, orientation: Int, dateTakenMs: Long): ByteArray? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90      -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180     -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270     -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE      -> { matrix.postScale(-1f, 1f); matrix.postRotate(-90f) }
                ExifInterface.ORIENTATION_TRANSVERSE     -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
                else -> { bitmap.recycle(); return null }
            }

            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            rotated.recycle()

            val result = out.toByteArray()

            // Stamp date + clear orientation tag via temp file
            stampExifAndClearOrientation(result, dateTakenMs) ?: result

        } catch (_: Exception) { null }
    }

    /**
     * Writes a corrected EXIF into a temp file: sets DateTimeOriginal, clears orientation to NORMAL.
     * Returns the modified bytes, or null on failure (caller uses plain bytes as fallback).
     */
    private fun stampExifAndClearOrientation(jpegBytes: ByteArray, dateTakenMs: Long): ByteArray? {
        var tmp: File? = null
        return try {
            tmp = File.createTempFile("ps_fix_", ".jpg", context.cacheDir)
            tmp.writeBytes(jpegBytes)
            ExifInterface(tmp.absolutePath).apply {
                // Clear rotation — pixels are now correct
                setAttribute(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString())
                // Stamp date if we have one
                if (dateTakenMs > 0) {
                    val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                        .format(Date(dateTakenMs))
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, fmt)
                    setAttribute(ExifInterface.TAG_DATETIME,           fmt)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, fmt)
                }
                saveAttributes()
            }
            tmp.readBytes()
        } catch (_: Exception) { null } finally { tmp?.delete() }
    }

    /** Updates DATE_TAKEN in MediaStore without replacing the file (orientation already correct). */
    private fun fixDateOnly(id: Long, dateTakenMs: Long) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        runCatching {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
            }, null, null)
        }
    }

    private fun parseExifDate(dateStr: String): Long? = try {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateStr)?.time
    } catch (_: Exception) { null }

    /**
     * Extracts a date from common Android filename patterns.
     * Handles:
     *   IMG_20250804_122335_511.jpg   → 2025-08-04 12:23:35
     *   IMG_20250804_122335.jpg       → 2025-08-04 12:23:35
     *   20260216_172618.jpg           → 2026-02-16 17:26:18
     *   IMG-20250821-WA0001.jpg       → 2025-08-21 (WhatsApp, no time)
     *   VID_20250804_122335.mp4       → 2025-08-04 12:23:35
     *   screenshot_20250804-122335.png → 2025-08-04 12:23:35
     */
    fun parseDateFromFilename(name: String): Long? {
        val stem = name.substringBeforeLast(".")

        // Pattern 1: 8-digit date + 6-digit time somewhere in name (most common)
        val dtRegex = Regex("""(\d{8})[_\-](\d{6})""")
        dtRegex.find(stem)?.let { m ->
            val date = m.groupValues[1]
            val time = m.groupValues[2]
            return try {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .parse("$date$time")?.time
            } catch (_: Exception) { null }
        }

        // Pattern 2: 8-digit date only (e.g. WhatsApp IMG-20250821-WA0001)
        val dRegex = Regex("""(\d{8})""")
        dRegex.find(stem)?.let { m ->
            val date = m.groupValues[1]
            // Sanity check: year must be 2000-2030
            val year = date.substring(0, 4).toIntOrNull() ?: return null
            if (year < 2000 || year > 2030) return null
            return try {
                SimpleDateFormat("yyyyMMdd", Locale.US).parse(date)?.time
            } catch (_: Exception) { null }
        }

        return null
    }

    /** Queries all non-pending images. Videos don't have orientation issues — images only. */
    private fun queryAllImages(): List<MediaFileInfo> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_TAKEN
        )
        val selection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            "${MediaStore.MediaColumns.IS_PENDING} = 0" else null

        val list = mutableListOf<MediaFileInfo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null,
            "${MediaStore.MediaColumns.DATE_ADDED} ASC"
        )?.use { c ->
            val idCol    = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol  = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol  = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol  = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol  = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol  = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                list += MediaFileInfo(
                    id           = c.getLong(idCol),
                    displayName  = c.getString(nameCol) ?: "unknown",
                    relativePath = c.getString(pathCol) ?: "",
                    mimeType     = c.getString(mimeCol) ?: "image/jpeg",
                    size         = c.getLong(sizeCol),
                    dateAdded    = c.getLong(dateCol),
                    dateTaken    = c.getLong(takenCol)
                )
            }
        }
        return list
    }

    /** Call this to force a full re-scan (e.g. after a code fix). */
    fun clearCheckedIds() {
        prefs.edit().remove(KEY_CHECKED).apply()
        pendingChecked.clear()
    }

    companion object {
        private const val KEY_CHECKED = "checked_ids"
    }
}
