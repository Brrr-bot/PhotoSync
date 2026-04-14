package com.photosync.client.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.photosync.shared.model.DateCorrection
import com.photosync.shared.model.MediaFileInfo
import java.io.InputStream

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

    companion object {
        private const val KEY_IDS = "ids"
    }

    // ── Pending-deletion queue ────────────────────────────────────────────────
    // Each entry: "<i|v>:<originalId>:<newId>"
    //   i/v      = image or video collection
    //   originalId = MediaStore ID of the camera original to delete
    //   newId      = MediaStore ID of the compressed replacement (kept IS_PENDING=1 until delete)
    //
    // The new file stays hidden (IS_PENDING=1) until the user approves deletion of the original,
    // preventing duplicate images from appearing in the gallery during the approval window.

    private fun enqueuePendingDelete(originalId: Long, newId: Long, isVideo: Boolean) {
        val set = prefs.getStringSet(KEY_IDS, emptySet())!!.toMutableSet()
        set.add("${if (isVideo) "v" else "i"}:$originalId:$newId")
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
            val newUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newId)
            runCatching {
                context.contentResolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
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
        // Sort oldest-first so hub can advance timestamp incrementally
        return results.sortedBy { it.dateAdded }
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
        val origUri = findOriginalUri(originalId)
            ?: throw IllegalStateException("Cannot find original file $originalId")
        val isVideo = origUri.toString().contains("video", ignoreCase = true)

        // Read metadata from the original so the new file looks identical in the gallery
        var displayName  = "img_${originalId}.jpg"
        var relativePath = "DCIM/"
        var dateTaken    = 0L   // milliseconds
        var dateAdded    = 0L   // seconds
        context.contentResolver.query(
            origUri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                displayName  = c.getString(0) ?: displayName
                relativePath = c.getString(1) ?: relativePath
                dateTaken    = c.getLong(2)
                dateAdded    = c.getLong(3)
            }
        }

        // MediaStore.Images.Media only allows inserts into DCIM/ or Pictures/.
        // Files from WhatsApp, Telegram, screenshots etc. live under Android/media/...
        // or other restricted paths — inserting there throws "Primary directory X not allowed".
        // Preserve the original path when it's allowed; otherwise fall back to DCIM/.
        val safeRelativePath = if (
            relativePath.startsWith("DCIM", ignoreCase = true) ||
            relativePath.startsWith("Pictures", ignoreCase = true)
        ) relativePath else "DCIM/"

        // DATE_TAKEN (ms) drives gallery sort order; prefer the hub-supplied value as it
        // comes from EXIF on the USB copy and is the most reliable source.
        val effectiveDateTaken = when {
            providedDateTaken > 0 -> providedDateTaken  // hub-supplied from USB EXIF — most reliable
            dateTaken > 0         -> dateTaken
            dateAdded > 0         -> dateAdded * 1000L  // convert seconds → milliseconds
            else                  -> System.currentTimeMillis()
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

        val newUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newValues
        ) ?: throw IllegalStateException("MediaStore insert failed")

        try {
            // Write compressed bytes — always succeeds because this app owns newUri
            context.contentResolver.openOutputStream(newUri, "wt")?.use { out ->
                out.write(compressedBytes)
            } ?: throw IllegalStateException("openOutputStream returned null for new URI")

            // Extract the new file's MediaStore ID so we can publish it later
            val newId = ContentUris.parseId(newUri)

            // Try to delete the original immediately.
            // Android 12+ with MANAGE_MEDIA: succeeds silently.
            // Android 10–11: throws SecurityException → needs createDeleteRequest approval.
            try {
                context.contentResolver.delete(origUri, null, null)
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
                }
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
                enqueuePendingDelete(originalId, newId, isVideo)
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
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
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
}
