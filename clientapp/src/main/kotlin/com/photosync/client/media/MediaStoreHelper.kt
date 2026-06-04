package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.photosync.shared.model.DateCorrection
import com.photosync.shared.model.MediaFileInfo
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** Result of [MediaStoreHelper.replaceFile]. */
enum class ReplaceResult {
    /** New compressed file written AND original deleted. */
    REPLACED,
    /** New compressed file written, but original delete needs user approval via [MediaStoreHelper.buildDeleteRequest]. */
    COMPRESSED_PENDING_DELETE
}

class MediaStoreHelper(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("pending_deletes", Context.MODE_PRIVATE)
    }

    private val compressionPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("compression_state", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_IDS = "ids"
        private const val KEY_REPLACED_ORIGINAL_IDS = "replaced_original_ids"
        private const val KEY_COMPRESSED_NEW_IDS = "compressed_new_ids"
        // Display names of replacement files — survives SharedPrefs clear of compressed_new_ids
        // so getMediaSince() can still filter them out if IDs are no longer present
        private const val KEY_COMPRESSED_NAMES = "compressed_display_names"
    }

    // ── Compression loop prevention ──────────────────────────────────────────
    // Tracks which original IDs have been replaced and which new IDs were created,
    // so we never re-replace an already-compressed file or serve it back to the hub.

    /**
     * True only if the original was marked replaced AND is actually gone from MediaStore.
     * If the original is still present (e.g. previous replace created a "(1)" copy but the
     * original delete failed), we treat it as NOT replaced so the hub retries and the v38
     * pre-delete path can fix it.
     */
    fun isAlreadyReplaced(originalId: Long): Boolean {
        val marked = compressionPrefs.getStringSet(KEY_REPLACED_ORIGINAL_IDS, emptySet())!!
            .contains(originalId.toString())
        if (!marked) return false
        // Original still findable in MediaStore? Then the replace didn't fully succeed.
        if (findOriginalUri(originalId) != null) {
            // Clear the stale mark so retries can proceed cleanly
            val set = compressionPrefs.getStringSet(KEY_REPLACED_ORIGINAL_IDS, emptySet())!!.toHashSet()
            set.remove(originalId.toString())
            compressionPrefs.edit().putStringSet(KEY_REPLACED_ORIGINAL_IDS, set).commit()
            return false
        }
        return true
    }

    private fun markReplaced(originalId: Long, newId: Long, newDisplayName: String = "") {
        val origSet = compressionPrefs.getStringSet(KEY_REPLACED_ORIGINAL_IDS, emptySet())!!.toMutableSet()
        origSet.add(originalId.toString())
        val newSet = compressionPrefs.getStringSet(KEY_COMPRESSED_NEW_IDS, emptySet())!!.toMutableSet()
        newSet.add(newId.toString())
        val nameSet = compressionPrefs.getStringSet(KEY_COMPRESSED_NAMES, emptySet())!!.toMutableSet()
        if (newDisplayName.isNotEmpty()) nameSet.add(newDisplayName)
        compressionPrefs.edit()
            .putStringSet(KEY_REPLACED_ORIGINAL_IDS, origSet)
            .putStringSet(KEY_COMPRESSED_NEW_IDS, newSet)
            .putStringSet(KEY_COMPRESSED_NAMES, nameSet)
            .apply()
    }

    // ── Pending-deletion queue ────────────────────────────────────────────────
    // Each entry: "<i|v>:<originalId>:<newId>:<dateAdded>:<dateModified>"
    //   i/v          = image or video collection
    //   originalId   = MediaStore ID of the camera original to delete
    //   newId        = MediaStore ID of the compressed replacement (kept IS_PENDING=1 until delete)
    //   dateAdded    = original DATE_ADDED (seconds) to restore after publish
    //   dateModified = original DATE_MODIFIED (seconds) to restore after publish
    //
    // The new file stays hidden (IS_PENDING=1) until the user approves deletion of the original,
    // preventing duplicate images from appearing in the gallery during the approval window.

    private fun enqueuePendingDelete(originalId: Long, newId: Long, isVideo: Boolean, dateAdded: Long = 0L, dateModified: Long = 0L) {
        val set = prefs.getStringSet(KEY_IDS, emptySet())!!.toMutableSet()
        set.add("${if (isVideo) "v" else "i"}:$originalId:$newId:$dateAdded:$dateModified")
        prefs.edit().putStringSet(KEY_IDS, set).apply()
    }

    fun hasPendingDeletions(): Boolean =
        prefs.getStringSet(KEY_IDS, emptySet())!!.isNotEmpty()

    fun clearPendingDeletions() =
        prefs.edit().remove(KEY_IDS).apply()

    /**
     * Publishes all pending compressed replacements by setting IS_PENDING=0.
     * Call this AFTER the user approves deletion of the originals so both operations
     * happen together — old file gone, new file visible — with no duplicate window.
     */
    fun publishPendingFiles() {
        val entries = prefs.getStringSet(KEY_IDS, emptySet()) ?: return
        for (entry in entries) {
            val parts = entry.split(":")
            val newId = parts.getOrNull(2)?.toLongOrNull() ?: continue
            val dateAdded = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            val dateModified = parts.getOrNull(4)?.toLongOrNull() ?: 0L
            val newUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newId)
            runCatching {
                context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                // Restore original timestamps so gallery position is preserved
                if (dateAdded > 0 || dateModified > 0) {
                    context.contentResolver.update(newUri, ContentValues().apply {
                        if (dateAdded > 0) put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                        if (dateModified > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
                    }, null, null)
                }
            }
        }
    }

    /**
     * Builds a [android.app.PendingIntent] that shows the system dialog to delete all
     * queued originals. Returns null if the queue is empty or API < Q.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun buildDeleteRequest(): android.app.PendingIntent? {
        val entries = prefs.getStringSet(KEY_IDS, emptySet()) ?: return null
        if (entries.isEmpty()) return null
        val uris = entries.mapNotNull { entry ->
            val parts = entry.split(":")
            val isVideo = parts[0] == "v"
            val originalId = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                       else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ContentUris.withAppendedId(base, originalId)
        }
        if (uris.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris) }.getOrNull()
    }

    /**
     * Returns all images and videos added after [sinceSeconds] (MediaStore epoch seconds).
     */
    fun getMediaSince(sinceSeconds: Long): List<MediaFileInfo> {
        val results = mutableListOf<MediaFileInfo>()
        results += queryUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sinceSeconds)
        results += queryUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sinceSeconds)
        // Exclude video poster images — they are local thumbnails, not originals to sync.
        // VideoSpaceManager writes the poster display name into KEY_POSTER_NAMES so we
        // can filter them here cheaply (set lookup, no EXIF reads).
        val posterNames = context.getSharedPreferences("video_space_state", Context.MODE_PRIVATE)
            .getStringSet(VideoSpaceManager.KEY_POSTER_NAMES, emptySet()) ?: emptySet()
        return results.filter { it.displayName !in posterNames }.sortedBy { it.dateAdded }
    }

    /** Opens an InputStream for a media file by its MediaStore ID. */
    fun openFileById(id: Long, isVideo: Boolean): InputStream? {
        val uri = if (isVideo) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens a file by ID trying images first, then video.
     * Returns Pair(InputStream, mimeType) or null.
     */
    fun openFileByIdAny(id: Long): Pair<InputStream, String>? {
        // Try images
        val imgUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        try {
            val stream = context.contentResolver.openInputStream(imgUri)
            if (stream != null) {
                val mime = context.contentResolver.getType(imgUri) ?: "image/jpeg"
                return Pair(stream, mime)
            }
        } catch (_: Exception) {}

        // Try video
        val vidUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        try {
            val stream = context.contentResolver.openInputStream(vidUri)
            if (stream != null) {
                val mime = context.contentResolver.getType(vidUri) ?: "video/mp4"
                return Pair(stream, mime)
            }
        } catch (_: Exception) {}

        return null
    }

    /** Returns the display name for a media ID (tries images then video). */
    fun getDisplayName(id: Long): String? {
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            val uri = ContentUris.withAppendedId(base, id)
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return null
    }

    /**
     * Replaces [originalId] with [compressedBytes].
     *
     * Strategy: INSERT a brand-new MediaStore record (this app owns it → write always works),
     * write the compressed bytes to the new URI, then DELETE the original.
     * Deletion requires MANAGE_MEDIA (Android 12+) or a prior createDeleteRequest grant (10–11).
     *
     * We never call openOutputStream on a file we don't own — that reliably fails on all
     * Android versions regardless of declared permissions.
     */
    fun replaceFile(originalId: Long, mimeType: String, compressedBytes: ByteArray, providedDateTaken: Long = 0L): ReplaceResult {
        if (isAlreadyReplaced(originalId)) return ReplaceResult.REPLACED

        val origUri = findOriginalUri(originalId)
            ?: throw IllegalStateException("Cannot find original file $originalId")
        val isVideo = origUri.toString().contains("video", ignoreCase = true)

        // Read metadata from the original so the new file looks identical in the gallery
        var displayName  = "img_${originalId}.${if (mimeType == "image/webp") "webp" else "jpg"}"
        var relativePath = "DCIM/"
        var dateTaken    = 0L   // milliseconds
        var dateAdded    = 0L   // seconds
        var dateModified = 0L   // seconds
        context.contentResolver.query(
            origUri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                // Update extension to match the incoming MIME type so we don't get .jpg.webp
                val rawName = c.getString(0) ?: displayName
                displayName = when (mimeType) {
                    "image/webp" -> rawName.replaceFirst(Regex("\\.(jpe?g|png|heic|heif)$", RegexOption.IGNORE_CASE), ".webp")
                    "image/jpeg" -> rawName.replaceFirst(Regex("\\.(webp|png|heic|heif)$", RegexOption.IGNORE_CASE), ".jpg")
                    else -> rawName
                }
                relativePath = c.getString(1) ?: relativePath
                dateTaken    = c.getLong(2)
                dateAdded    = c.getLong(3)
                dateModified = c.getLong(4)
            }
        }

        // Pick the right MediaStore collection and a path it will accept.
        //
        // Images collection only allows DCIM/ and Pictures/.
        // Screenshots live in DCIM/Screenshots/ → Images ✓
        // WhatsApp/Telegram live in Pictures/... → Images ✓
        // Files in Download/, Movies/, or anything else go to the Downloads collection
        // (API 29+) so they stay in their original folder instead of being forced to DCIM/.
        // Videos always use the Video collection regardless of path.
        val inImageCollection = relativePath.startsWith("DCIM", ignoreCase = true) ||
                                relativePath.startsWith("Pictures", ignoreCase = true)
        val useDownloads = !isVideo && !inImageCollection &&
                           Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val safeRelativePath = relativePath  // always try to preserve the exact original path

        // Read DATE_TAKEN directly from EXIF embedded in the compressed bytes.
        // This is the most reliable source — MediaCompressor copies it from the USB original,
        // so it bypasses any MediaStore DATE_TAKEN corruption on the phone.
        val exifDateTaken = try {
            val exif = ExifInterface(ByteArrayInputStream(compressedBytes))
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            raw?.let { parseExifDateStr(it) } ?: 0L
        } catch (_: Exception) { 0L }

        val effectiveDateTaken = when {
            exifDateTaken > 0     -> exifDateTaken      // from EXIF in the file — most reliable
            providedDateTaken > 0 -> providedDateTaken  // hub-supplied fallback
            dateTaken > 0         -> dateTaken
            dateAdded > 0         -> dateAdded * 1000L
            else                  -> System.currentTimeMillis()
        }

        // ── Strategy A: TRUE in-place overwrite (preferred) ──────────────────────
        // With MANAGE_EXTERNAL_STORAGE granted, we can write directly to the file
        // path on /storage. With only MANAGE_MEDIA we try openFileDescriptor("wt")
        // first — it works on some OEMs even without all-files access. Either way:
        // same MediaStore ID, same display name, no insert, no delete, no "(1)".
        try {
            // Try direct file path write first (works with MANAGE_EXTERNAL_STORAGE)
            val realPath = resolveRealPath(origUri)
            val wrote = if (realPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && android.os.Environment.isExternalStorageManager()) {
                try {
                    java.io.FileOutputStream(java.io.File(realPath)).use { it.write(compressedBytes) }
                    true
                } catch (_: Exception) { false }
            } else false
            if (!wrote) {
                context.contentResolver.openFileDescriptor(origUri, "wt")?.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).use { it.write(compressedBytes) }
                } ?: throw IllegalStateException("openFileDescriptor returned null")
            }
            // Update size + dates so MediaStore reflects the new file.
            // DATE_TAKEN drives gallery sort; preserve original DATE_ADDED/MODIFIED.
            context.contentResolver.update(origUri, ContentValues().apply {
                put(MediaStore.MediaColumns.SIZE,       compressedBytes.size.toLong())
                put(MediaStore.MediaColumns.MIME_TYPE,  mimeType)
                put(MediaStore.MediaColumns.DATE_TAKEN, effectiveDateTaken)
                if (dateAdded > 0)    put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                if (dateModified > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
            }, null, null)
            markReplaced(originalId, originalId, displayName)  // newId == originalId for in-place
            return ReplaceResult.REPLACED
        } catch (_: SecurityException) {
            // No MANAGE_MEDIA — fall through to insert-then-delete path
        } catch (_: Exception) {
            // Any other failure — fall through to insert-then-delete path
        }

        // ── Strategy B: insert-then-delete (fallback for no MANAGE_MEDIA) ────────
        // Try deleting the original FIRST so the insert below uses the exact display name
        // without MediaStore auto-appending " (1)". On 10–11 it throws SecurityException
        // and we fall back to the old insert-then-delete-after path.
        var originalDeleted = false
        try {
            context.contentResolver.delete(origUri, null, null)
            originalDeleted = true
        } catch (_: SecurityException) {
            // Needs user approval — proceed with insert-then-delete, rename after delete
        }

        // Insert a new record — this app becomes the owner so openOutputStream always works
        val newValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE,    mimeType)
            put(MediaStore.MediaColumns.DATE_TAKEN,   effectiveDateTaken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, safeRelativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            // DATE_ADDED is seconds; Android may override it but setting it gives the best chance
            if (dateAdded > 0) put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
        }

        val collection = when {
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            useDownloads -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        // If inserting into Images collection and path isn't allowed, fall back to DCIM/
        val insertValues = if (!isVideo && !useDownloads && !inImageCollection) {
            ContentValues(newValues).apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/")
            }
        } else newValues
        val newUri = context.contentResolver.insert(collection, insertValues)
            ?: throw IllegalStateException("MediaStore insert failed")

        try {
            // Write compressed bytes — always succeeds because this app owns newUri
            context.contentResolver.openOutputStream(newUri, "wt")?.use { out ->
                out.write(compressedBytes)
            } ?: throw IllegalStateException("openOutputStream returned null for new URI")

            // Extract the new file's MediaStore ID so we can publish it later
            val newId = ContentUris.parseId(newUri)

            // If we already deleted the original up front, publish now. Otherwise try to
            // delete after writing.
            try {
                if (!originalDeleted) context.contentResolver.delete(origUri, null, null)
                // Delete succeeded — publish the new file now so it's visible in the gallery.
                // DATE_TAKEN is set here as a ContentValues fallback; the hub also stamped
                // DateTimeOriginal into the JPEG EXIF so the MediaStore scanner reads it too.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(newUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        put(MediaStore.MediaColumns.SIZE,       compressedBytes.size.toLong())
                        put(MediaStore.MediaColumns.DATE_TAKEN, effectiveDateTaken)
                        if (dateAdded > 0) put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                    }, null, null)
                    // Separate update to force original timestamps — Android may reset them
                    // during IS_PENDING transition, so this must come after
                    if (dateAdded > 0 || dateModified > 0) {
                        context.contentResolver.update(newUri, ContentValues().apply {
                            if (dateAdded > 0) put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                            if (dateModified > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
                        }, null, null)
                    }
                }
                markReplaced(originalId, newId, displayName)
                return ReplaceResult.REPLACED
            } catch (sec: SecurityException) {
                // Delete needs user approval.  Keep the new file as IS_PENDING=1 so it stays
                // hidden from the gallery until the original is confirmed deleted — no duplicates.
                // publishPendingFiles() will set IS_PENDING=0 after the user approves.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Update size and date but leave IS_PENDING=1
                    context.contentResolver.update(newUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.SIZE,       compressedBytes.size.toLong())
                        put(MediaStore.MediaColumns.DATE_TAKEN, effectiveDateTaken)
                        if (dateAdded > 0) put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                    }, null, null)
                }
                enqueuePendingDelete(originalId, newId, isVideo, dateAdded, dateModified)
                markReplaced(originalId, newId, displayName)
                return ReplaceResult.COMPRESSED_PENDING_DELETE
            }

        } catch (e: Exception) {
            // If anything failed *before* writing was complete, remove the incomplete new file
            runCatching { context.contentResolver.delete(newUri, null, null) }
            throw e
        }
    }

    /**
     * Builds a [android.app.PendingIntent] that, when launched, shows the system dialog
     * requesting write access to all images in MediaStore. Required on Android 10–11 where
     * MANAGE_MEDIA doesn't exist. Pass the result to [android.app.Activity.startIntentSenderForResult].
     * Once the user approves, [replaceFile] will succeed without MANAGE_MEDIA.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun buildWriteRequest(): android.app.PendingIntent? {
        return runCatching {
            val uris = mutableListOf<Uri>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    uris += ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(col))
                }
            }
            if (uris.isEmpty()) return null
            MediaStore.createWriteRequest(context.contentResolver, uris)
        }.getOrNull()
    }

    /**
     * Updates DATE_TAKEN in MediaStore for each entry in [corrections], matching by DISPLAY_NAME.
     * Only updates files this app owns (inserted via replaceFile). Returns the count of updated rows.
     */
    fun fixDates(corrections: List<DateCorrection>): Int {
        var updated = 0
        for (correction in corrections) {
            for (base in listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATE_TAKEN, correction.correctDateTaken)
                    }
                    val rows = context.contentResolver.update(
                        base,
                        values,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(correction.displayName)
                    )
                    updated += rows
                } catch (_: Exception) { /* skip files we don't own or that no longer exist */ }
            }
        }
        return updated
    }

    /** Parses an EXIF date string ("yyyy:MM:dd HH:mm:ss") into epoch ms. Returns 0 on failure. */
    private fun parseExifDateStr(raw: String): Long = try {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())?.time ?: 0L
    } catch (_: Exception) { 0L }

    /** Resolves the absolute /storage path for a MediaStore URI. Null if unavailable. */
    private fun resolveRealPath(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun findOriginalUri(id: Long): Uri? {
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            val uri = ContentUris.withAppendedId(base, id)
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) return uri }
        }
        return null
    }

    private fun queryUri(
        baseUri: android.net.Uri,
        sinceSeconds: Long
    ): List<MediaFileInfo> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_TAKEN
        )
        // Exclude IS_PENDING files — these are compressed replacements mid-flight.
        // Belt-and-suspenders alongside the compressed_new_ids filter in getMediaSince().
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        else
            "${MediaStore.MediaColumns.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(sinceSeconds.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        val list = mutableListOf<MediaFileInfo>()
        context.contentResolver.query(
            baseUri, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)

            while (cursor.moveToNext()) {
                list += MediaFileInfo(
                    id          = cursor.getLong(idCol),
                    displayName = cursor.getString(nameCol) ?: "unknown",
                    relativePath = cursor.getString(pathCol) ?: "",
                    mimeType    = cursor.getString(mimeCol) ?: "application/octet-stream",
                    size        = cursor.getLong(sizeCol),
                    dateAdded   = cursor.getLong(dateCol),
                    dateTaken   = cursor.getLong(takenCol)   // ms; 0 when no EXIF date
                )
            }
        }
        return list
    }

    /**
     * One-time cleanup: finds original files that have a compressed `(1)` twin and deletes
     * the originals so the gallery no longer shows duplicates.
     *
     * Background: the old compression loop created `filename (1).jpg` replacements while the
     * originals still existed (MediaStore auto-renamed to avoid conflicts). Both files ended up
     * visible in the gallery. This function finds every `filename (1).jpg` that has a matching
     * `filename.jpg` original and removes the original, keeping only the compressed copy.
     *
     * Returns count of originals deleted.
     */
    fun cleanupOrphanedOriginals(): Int {
        var deleted = 0
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE
        )
        // Build a map of displayName -> (id, size) for all visible images
        val allImages = mutableMapOf<String, Pair<Long, Long>>() // name -> (id, size)
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "${MediaStore.MediaColumns.IS_PENDING} = 0" else null
            context.contentResolver.query(base, projection, sel, null, null)
                ?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        val name = c.getString(nameCol) ?: continue
                        val id   = c.getLong(idCol)
                        val size = c.getLong(sizeCol)
                        allImages[name] = Pair(id, size)
                    }
                }
        }

        // For each file whose name ends with " (1).ext", check if the plain original exists
        // and is LARGER (meaning it's the uncompressed original, not another compressed copy).
        // Pattern: "photo (1).jpg" → look for "photo.jpg"
        val suffixRegex = Regex("""^(.+) \(1\)(\.[^.]+)$""")
        for ((compressedName, compressedEntry) in allImages) {
            val match = suffixRegex.matchEntire(compressedName) ?: continue
            val stem = match.groupValues[1]
            val ext  = match.groupValues[2]
            val originalName = "$stem$ext"

            val originalEntry = allImages[originalName] ?: continue
            val (originalId, originalSize) = originalEntry
            val (_, compressedSize) = compressedEntry

            // Only delete original if compressed version is actually smaller
            // (guards against deleting originals when the "(1)" file is unrelated)
            if (compressedSize < originalSize) {
                val origUri = findOriginalUri(originalId) ?: continue
                try {
                    val rows = context.contentResolver.delete(origUri, null, null)
                    if (rows > 0) deleted++
                } catch (_: SecurityException) {
                    // MANAGE_MEDIA not granted — skip; user will need to approve via batch request
                }
            }
        }

        // Second pass: rename any remaining "foo (1).jpg" → "foo.jpg" where no plain
        // original exists in MediaStore. This covers v37-and-earlier compression runs
        // that left (1)-suffixed files in the gallery permanently.
        val allImagesAfter = mutableMapOf<String, Long>()
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "${MediaStore.MediaColumns.IS_PENDING} = 0" else null
            context.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                sel, null, null
            )?.use { c ->
                val idCol   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    allImagesAfter[name] = c.getLong(idCol)
                }
            }
        }
        for ((name, id) in allImagesAfter.toMap()) {
            val match = suffixRegex.matchEntire(name) ?: continue
            val cleanName = match.groupValues[1] + match.groupValues[2]
            if (allImagesAfter.containsKey(cleanName)) continue  // plain still exists — don't clash
            val uri = findOriginalUri(id) ?: continue
            try {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                }, null, null)
            } catch (_: Exception) { /* best-effort */ }
        }
        return deleted
    }

    /**
     * Builds a batch delete request for all orphaned originals that could not be deleted
     * automatically (Android 10-11 without MANAGE_MEDIA). Returns null if nothing to approve.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun buildOrphanCleanupRequest(): android.app.PendingIntent? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val allImages = mutableMapOf<String, Pair<Long, Long>>()
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            val sel = "${MediaStore.MediaColumns.IS_PENDING} = 0"
            context.contentResolver.query(base, projection, sel, null, null)
                ?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        allImages[c.getString(nameCol) ?: continue] = Pair(c.getLong(idCol), c.getLong(sizeCol))
                    }
                }
        }

        val toDelete = mutableListOf<android.net.Uri>()
        val suffixRegex = Regex("""^(.+) \(1\)(\.[^.]+)$""")
        for ((compressedName, compressedEntry) in allImages) {
            val match = suffixRegex.matchEntire(compressedName) ?: continue
            val originalName = "${match.groupValues[1]}${match.groupValues[2]}"
            val originalEntry = allImages[originalName] ?: continue
            if (compressedEntry.second < originalEntry.second) {
                findOriginalUri(originalEntry.first)?.let { toDelete += it }
            }
        }

        if (toDelete.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(context.contentResolver, toDelete) }.getOrNull()
    }
}
