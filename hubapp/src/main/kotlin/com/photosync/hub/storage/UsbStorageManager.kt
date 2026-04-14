package com.photosync.hub.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photosync.shared.model.MediaFileInfo
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsbStorageManager(
    private val context: Context,
    private val prefs: SharedPreferences
) {

    private val treeUri: Uri? get() = loadUri()
    private val gson = Gson()

    fun isReady(): Boolean {
        val uri = treeUri ?: return false
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc != null && doc.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    fun persistUri(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(PREF_USB_URI, uri.toString()).apply()
    }

    /**
     * Writes [stream] to [deviceName]/[file.displayName] on the USB drive.
     * Skips if the file already exists (idempotent).
     * Returns true if the file was actually written, false if skipped.
     */
    fun writeFile(deviceName: String, file: MediaFileInfo, stream: InputStream): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri!!)
            ?: throw IllegalStateException("USB root not accessible")

        val deviceFolder = root.findFile(deviceName)
            ?: root.createDirectory(deviceName)
            ?: throw IllegalStateException("Cannot create device folder '$deviceName'")

        // Skip if already backed up
        if (deviceFolder.findFile(file.displayName) != null) return false

        // Use "application/octet-stream" so SAF doesn't append a second extension.
        // Many OEM implementations add the MIME type's extension even when displayName
        // already has one (e.g. "IMG_001.jpg" → "IMG_001.jpg.jpg").
        val newFile = deviceFolder.createFile("application/octet-stream", file.displayName)
            ?: throw IllegalStateException("Cannot create file '${file.displayName}'")

        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
            stream.copyTo(out)
        } ?: throw IllegalStateException("Cannot open output stream")

        // Stamp EXIF DateTimeOriginal on JPEG/HEIC images so gallery apps sort by the
        // original capture date rather than the SAF write time (which is always "now").
        if (file.mimeType.startsWith("image/") && file.mimeType != "image/gif") {
            stampExifDate(newFile.uri, file)
        }

        return true
    }

    /**
     * Opens [uri] read-write via SAF FileDescriptor and writes EXIF [DateTimeOriginal] /
     * [DateTime] tags only when they are absent or zero.  Uses [MediaFileInfo.dateTaken]
     * (ms) first; falls back to [MediaFileInfo.dateAdded] (s → ms).
     * Silently no-ops if the format doesn't support EXIF (e.g. PNG).
     */
    private fun stampExifDate(uri: Uri, file: MediaFileInfo) {
        try {
            val epochMs = when {
                file.dateTaken > 0 -> file.dateTaken
                file.dateAdded > 0 -> file.dateAdded * 1000L
                else               -> return   // no date info at all — don't touch the file
            }
            val formatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .format(Date(epochMs))

            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                // Only overwrite if the tag is currently absent / zero
                val existing = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                if (existing.isNullOrBlank() || existing.startsWith("0000")) {
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
                    exif.setAttribute(ExifInterface.TAG_DATETIME,          formatted)
                    exif.saveAttributes()
                }
            }
        } catch (_: Exception) {
            // PNG, GIF, corrupt EXIF — not fatal
        }
    }

    /**
     * Reads a file from the USB drive and returns its bytes, or null if not found.
     */
    fun readFile(deviceName: String, displayName: String): ByteArray? {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return null) ?: return null
            val folder = root.findFile(deviceName) ?: return null
            val file = folder.findFile(displayName) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the set of all filenames already in [deviceName]'s folder on the USB drive.
     * Call once per sync session and cache — much faster than calling findFile() per file.
     */
    fun getExistingFileNames(deviceName: String): Set<String> {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return emptySet()) ?: return emptySet()
            val folder = root.findFile(deviceName) ?: return emptySet()
            folder.listFiles().mapNotNull { it.name }.toHashSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ── USB-resident sync manifest ────────────────────────────────────────────
    // Stored as "photosync_state.json" (no leading dot — SAF findFile misses hidden files)
    // Survives app reinstalls because it lives on the USB drive, not SharedPreferences.

    /** Returns the last sync timestamp (ms) for [deviceName], or 0 if not found. */
    fun readManifestLastSync(deviceName: String): Long {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return 0L) ?: return 0L
            val file = root.findFile(MANIFEST_FILENAME) ?: return 0L
            val json = context.contentResolver.openInputStream(file.uri)
                ?.use { it.bufferedReader().readText() } ?: return 0L
            val map: Map<String, Long> = gson.fromJson(
                json, object : TypeToken<Map<String, Long>>() {}.type
            ) ?: return 0L
            map[deviceName] ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Persists [lastSyncMs] for [deviceName] into the USB manifest. */
    fun writeManifestLastSync(deviceName: String, lastSyncMs: Long) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return) ?: return

            // Read existing entries
            val existing = mutableMapOf<String, Long>()
            root.findFile(MANIFEST_FILENAME)?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    runCatching {
                        val parsed: Map<String, Long> = gson.fromJson(
                            stream.bufferedReader().readText(),
                            object : TypeToken<Map<String, Long>>() {}.type
                        )
                        existing.putAll(parsed)
                    }
                }
            }

            existing[deviceName] = lastSyncMs

            // Delete old file and write fresh (SAF can't seek/truncate)
            root.findFile(MANIFEST_FILENAME)?.delete()
            root.createFile("application/json", MANIFEST_FILENAME)?.let { newFile ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    out.write(gson.toJson(existing).toByteArray())
                }
            }
        } catch (_: Exception) { /* non-fatal — next sync will just re-scan */ }
    }

    /**
     * Reads the EXIF DateTimeOriginal from a single USB file and returns epoch ms, or 0 if absent.
     */
    fun readExifDateTaken(deviceName: String, displayName: String): Long {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return 0L) ?: return 0L
            val folder = root.findFile(deviceName) ?: return 0L
            val file = folder.findFile(displayName) ?: return 0L
            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: return 0L
                if (raw.isBlank() || raw.startsWith("0000")) return 0L
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time ?: 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * Returns a map of displayName → epoch-ms for every image file in [deviceName]'s USB folder
     * that has a valid EXIF DateTimeOriginal. Files without EXIF dates are omitted.
     */
    fun getAllExifDates(deviceName: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return result) ?: return result
            val folder = root.findFile(deviceName) ?: return result
            val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
            for (file in folder.listFiles()) {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in imageExtensions) continue
                try {
                    context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                            ?: return@use
                        if (raw.isBlank() || raw.startsWith("0000")) return@use
                        val ms = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
                            ?: return@use
                        result[name] = ms
                    }
                } catch (_: Exception) { /* corrupt EXIF — skip */ }
            }
        } catch (_: Exception) { /* USB not accessible */ }
        return result
    }

    private fun loadUri(): Uri? {
        val str = prefs.getString(PREF_USB_URI, null) ?: return null
        return Uri.parse(str)
    }

    companion object {
        const val PREF_USB_URI = "usb_tree_uri"
        const val MANIFEST_FILENAME = "photosync_state.json"   // no leading dot — SAF findFile skips hidden files
    }
}
