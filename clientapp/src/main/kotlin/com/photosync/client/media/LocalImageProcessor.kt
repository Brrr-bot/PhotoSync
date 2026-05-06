package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import com.photosync.client.util.RemoteLogger
import com.photosync.shared.model.MediaFileInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locally fixes two problems without help from the hub:
 *
 *  1. EXIF orientation — only fixes by rotating pixels and calling replaceFile().
 *     Never touches files with "(1)" in the name (already processed copies).
 *
 *  2. Missing DATE_TAKEN — uses a direct ContentValues update only.
 *     Does NOT create replacement files for date-only issues — that made things worse
 *     because the new file gets date_added=now and appears at the wrong position.
 *
 * Safety rules:
 *  - Skips files in replaced_original_ids and compressed_new_ids (already processed by hub/us)
 *  - Skips files with "(1)" in the display name (replacement copies)
 *  - Only calls replaceFile() when rotation is genuinely needed
 *  - A bumped LOCAL_FIX_VERSION in ClientForegroundService forces a fresh rescan
 */
class LocalImageProcessor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mediaStore = MediaStoreHelper(context)
    private val compressionPrefs = context.getSharedPreferences("compression_state", Context.MODE_PRIVATE)

    private val pendingChecked = mutableSetOf<String>()

    // ── Public API ────────────────────────────────────────────────────────────

    fun processUnfixed(onProgress: ((done: Int, total: Int, msg: String) -> Unit)? = null): Int {
        val checkedIds  = prefs.getStringSet(KEY_CHECKED, emptySet())!!
        val replacedIds = compressionPrefs.getStringSet("replaced_original_ids", emptySet())!!
        val newIds      = compressionPrefs.getStringSet("compressed_new_ids", emptySet())!!
        val skipIds     = checkedIds + replacedIds + newIds

        val images = queryAllImages().filter { image ->
            image.id.toString() !in skipIds &&
            !image.displayName.contains("(1)", ignoreCase = false)  // never touch replacement copies
        }

        val total = images.size
        if (total == 0) return 0

        var done  = 0
        var fixed = 0

        for (image in images) {
            done++
            if (done % 25 == 0) onProgress?.invoke(done, total, "Scanning $done/$total…")

            val bytes = try {
                mediaStore.openFileById(image.id, false)?.use { it.readBytes() }
            } catch (_: Exception) { null }

            if (bytes == null || bytes.isEmpty()) {
                markChecked(image.id); continue
            }

            val exif = try {
                ExifInterface(ByteArrayInputStream(bytes))
            } catch (_: Exception) {
                markChecked(image.id); continue
            }

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            val exifDateRaw  = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val exifDateMs   = exifDateRaw?.let { parseExifDate(it) }
            val filenameDateMs = parseDateFromFilename(image.displayName)

            // Best date: MediaStore → EXIF → filename
            val effectiveDateTaken = when {
                image.dateTaken > 0 -> image.dateTaken
                exifDateMs != null  -> exifDateMs
                else                -> filenameDateMs ?: 0L
            }

            // Pixel dimensions without full decode
            val opts = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val pw = opts.outWidth
            val ph = opts.outHeight

            val orientName = when (orientation) {
                ExifInterface.ORIENTATION_NORMAL       -> "NORMAL(1)"
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "FLIP_H(2)"
                ExifInterface.ORIENTATION_ROTATE_180   -> "ROTATE_180(3)"
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> "FLIP_V(4)"
                ExifInterface.ORIENTATION_TRANSPOSE    -> "TRANSPOSE(5)"
                ExifInterface.ORIENTATION_ROTATE_90    -> "ROTATE_90(6)"
                ExifInterface.ORIENTATION_TRANSVERSE   -> "TRANSVERSE(7)"
                ExifInterface.ORIENTATION_ROTATE_270   -> "ROTATE_270(8)"
                ExifInterface.ORIENTATION_UNDEFINED    -> "UNDEFINED(0)"
                else -> "UNKNOWN($orientation)"
            }
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val msDateStr = if (image.dateTaken > 0) dateFmt.format(Date(image.dateTaken)) else "null"
            val exifDateStr = exifDateRaw ?: "null"
            val fnDateStr = if (filenameDateMs != null) dateFmt.format(Date(filenameDateMs)) else "null"
            RemoteLogger.i("[SCAN] id=${image.id} name=${image.displayName} " +
                "size=${image.size}B ${pw}x${ph}px " +
                "orient=$orientName ms_date=$msDateStr exif_date=$exifDateStr fn_date=$fnDateStr " +
                "mime=${image.mimeType} path=${image.relativePath}")

            // Auto-rotation disabled: Samsung Gallery (and all modern viewers) handle
            // EXIF orientation correctly. Baking rotation into pixels causes corruption
            // on devices where the camera sets ROTATE_90 for portrait but pixels are correct.
            // Rotation is still available via the manual Fix Orientation menu action.
            val needsRotation = false
            val needsDateFix  = effectiveDateTaken > 0 && image.dateTaken == 0L

            when {
                needsRotation -> {
                    // Rotate pixels, stamp EXIF date, replace the file via INSERT+DELETE
                    val rotated = rotateAndEncode(bytes, orientation, effectiveDateTaken)
                    if (rotated != null) {
                        try {
                            mediaStore.replaceFile(
                                image.id,
                                image.mimeType.ifEmpty { "image/jpeg" },
                                rotated,
                                effectiveDateTaken
                            )
                            onProgress?.invoke(done, total, "Rotated: ${image.displayName}")
                            fixed++
                        } catch (_: Exception) {
                            markChecked(image.id)
                        }
                    } else {
                        markChecked(image.id)
                    }
                }

                needsDateFix -> {
                    // Use IS_PENDING trick: re-hide → stamp EXIF → publish with DATE_TAKEN.
                    // With MANAGE_MEDIA granted (Android 15, confirmed) this works on any file,
                    // not just files we own. The scanner reads EXIF on publish so DATE_TAKEN sticks.
                    // Falls back to direct update if IS_PENDING trick fails.
                    val stamped = stampFileDate(image.id, effectiveDateTaken)
                    if (stamped) {
                        onProgress?.invoke(done, total, "Date fixed: ${image.displayName}")
                        fixed++
                    } else if (tryUpdateDateTaken(image.id, effectiveDateTaken)) {
                        onProgress?.invoke(done, total, "Date updated: ${image.displayName}")
                        fixed++
                    }
                    markChecked(image.id)
                }

                else -> markChecked(image.id)
            }
        }

        // Second pass: fix DATE_TAKEN on files we created (compressed_new_ids) via EXIF stamp.
        val ownedFixed = fixOwnedFilesWithNullDate(newIds)
        if (ownedFixed > 0) onProgress?.invoke(total, total, "Fixed dates on $ownedFixed owned file(s)")
        fixed += ownedFixed

        flushChecked()
        return fixed
    }

    fun clearCheckedIds() {
        prefs.edit().remove(KEY_CHECKED).apply()
        pendingChecked.clear()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Tries to update DATE_TAKEN directly in MediaStore.
     * Returns true if the update affected at least one row.
     * On Android 10+ this fails with SecurityException for files we don't own — caught silently.
     */
    private fun tryUpdateDateTaken(id: Long, dateTakenMs: Long): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
            }, null, null) > 0
        } catch (_: SecurityException) { false }
    }

    /**
     * Fixes DATE_TAKEN on files we created via replaceFile() (in compressed_new_ids).
     * Uses the IS_PENDING trick: re-hide → stamp EXIF date into the file bytes → publish with
     * DATE_TAKEN set. The media scanner reads EXIF on publish and sets DATE_TAKEN correctly,
     * so it survives any subsequent scanner re-runs — unlike a plain ContentValues update.
     */
    private fun fixOwnedFilesWithNullDate(ownedIds: Set<String>): Int {
        if (ownedIds.isEmpty()) return 0
        var fixed = 0
        val all = queryAllImages()
        for (image in all) {
            if (image.id.toString() !in ownedIds) continue
            if (image.dateTaken > 0) continue          // already correct
            val date = parseDateFromFilename(image.displayName) ?: continue
            if (stampFileDate(image.id, date)) fixed++
        }
        return fixed
    }

    /**
     * Stamps a date into a file's EXIF and MediaStore DATE_TAKEN.
     *
     * Two strategies:
     *
     * A) Owned files (we created via replaceFile) — IS_PENDING trick:
     *    set IS_PENDING=1 → read → stampExif → write → IS_PENDING=0 + DATE_TAKEN.
     *    Scanner reads EXIF on publish → date is permanent.
     *
     * B) Non-owned files (Telegram, WhatsApp, Zalo, camera originals) — file descriptor:
     *    openFileDescriptor("rw") with MANAGE_MEDIA → ExifInterface writes directly into the
     *    live file → then update DATE_TAKEN in ContentValues.
     *    MANAGE_MEDIA is confirmed granted on this device (Android 15).
     */
    private fun stampFileDate(id: Long, dateTakenMs: Long): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            // Strategy A: try IS_PENDING trick (only works if we own the file)
            val setPending = try {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }, null, null) > 0
            } catch (_: Exception) { false }

            if (setPending) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        // Undo pending
                        context.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                        return false
                    }
                val stamped = stampExif(bytes, dateTakenMs) ?: bytes
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(stamped) }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
                }, null, null)
                return true
            }

            // Strategy B: write EXIF via file descriptor (requires MANAGE_MEDIA)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && dateTakenMs > 0) {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor).apply {
                        val fmt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(dateTakenMs))
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, fmt)
                        setAttribute(ExifInterface.TAG_DATETIME,           fmt)
                        setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, fmt)
                        saveAttributes()
                    }
                }
            }
            // Also update MediaStore metadata (belt + suspenders)
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMs)
            }, null, null) > 0

        } catch (_: Exception) { false }
    }

    /**
     * Rotates [bytes] according to EXIF [orientation], re-encodes at 95% quality.
     * Stamps [dateTakenMs] into EXIF and sets orientation to NORMAL in the output.
     * Returns null if decoding or encoding fails (caller should skip the file).
     */
    private fun rotateAndEncode(bytes: ByteArray, orientation: Int, dateTakenMs: Long): ByteArray? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90       -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180      -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270      -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE  -> { matrix.postScale(-1f, 1f); matrix.postRotate(-90f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
                else -> { bitmap.recycle(); return null }
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            rotated.recycle()
            // Stamp date + clear orientation tag so scanner reads the correct date
            stampExif(out.toByteArray(), dateTakenMs) ?: out.toByteArray()
        } catch (_: Exception) { null }
    }

    /**
     * Writes EXIF DateTimeOriginal and clears orientation tag via a temp file.
     * Returns modified bytes, or null on failure.
     */
    private fun stampExif(jpegBytes: ByteArray, dateTakenMs: Long): ByteArray? {
        var tmp: File? = null
        return try {
            tmp = File.createTempFile("ps_fix_", ".jpg", context.cacheDir)
            tmp.writeBytes(jpegBytes)
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString())
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

    private fun parseExifDate(s: String): Long? = try {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time
    } catch (_: Exception) { null }

    /**
     * Parses a date from common Android filename patterns.
     * Returns null if no recognisable date found or date is outside 2000–2035.
     *
     * Supported patterns:
     *   IMG_20250804_122335.jpg          → 2025-08-04 12:23:35
     *   20260216_172618.jpg              → 2026-02-16 17:26:18
     *   IMG-20250821-WA0001.jpg          → 2025-08-21
     *   Screenshot_20260221_111619….jpg  → 2026-02-21 11:16:19
     *   IMG_1771417696748_1771417820207  → read first 13-digit number as ms epoch
     */
    fun parseDateFromFilename(name: String): Long? {
        val stem = name.substringBeforeLast(".").replace(Regex("""\s*\(\d+\)\s*"""), "").trim()

        // Pattern 0: 13-digit ms-epoch timestamp anywhere in the name.
        // e.g. IMG_1771417696748_1771417820207 → 1771417696748 ms = Feb 2026
        // Must check BEFORE the 8-digit pattern or regex will match substrings of it.
        Regex("""(\d{13})""").find(stem)?.let { m ->
            val ms = m.groupValues[1].toLongOrNull() ?: return@let
            val year = java.util.Calendar.getInstance()
                .also { it.timeInMillis = ms }
                .get(java.util.Calendar.YEAR)
            if (year in 2000..2035) return ms
        }

        // Pattern 1: YYYYMMDD + _ or - + HHMMSS (strict year range check)
        Regex("""(\d{8})[_\-](\d{6})""").find(stem)?.let { m ->
            val year = m.groupValues[1].substring(0, 4).toIntOrNull() ?: return@let
            if (year !in 2000..2035) return@let   // reject e.g. year 7714 from ms substrings
            return try {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .parse(m.groupValues[1] + m.groupValues[2])?.time
            } catch (_: Exception) { null }
        }

        // Pattern 2: 8-digit date only (e.g. WhatsApp IMG-20250821-WA0001)
        Regex("""(\d{8})""").find(stem)?.let { m ->
            val year = m.groupValues[1].substring(0, 4).toIntOrNull() ?: return null
            if (year !in 2000..2035) return null
            return try {
                SimpleDateFormat("yyyyMMdd", Locale.US).parse(m.groupValues[1])?.time
            } catch (_: Exception) { null }
        }

        return null
    }

    private fun markChecked(id: Long) {
        pendingChecked.add(id.toString())
        if (pendingChecked.size >= 50) flushChecked()
    }

    private fun flushChecked() {
        if (pendingChecked.isEmpty()) return
        val set = (prefs.getStringSet(KEY_CHECKED, emptySet())!! + pendingChecked).toMutableSet()
        prefs.edit().putStringSet(KEY_CHECKED, set).apply()
        pendingChecked.clear()
    }

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

    companion object {
        private const val PREFS_NAME = "local_fix_state"
        private const val KEY_CHECKED = "checked_ids"
    }
}
