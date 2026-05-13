package com.photosync.hub.storage

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photosync.shared.model.MediaFileInfo
import java.io.ByteArrayOutputStream
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
     * Writes [stream] to [deviceName]/YYYY-MM-DD/[file.displayName] on the USB drive.
     * New files go into a date subfolder derived from the photo's capture date so that
     * browsing by folder in a file explorer shows photos in chronological order.
     * Existing flat-folder files are detected and skipped (backward compatible).
     * Returns true if the file was actually written, false if skipped.
     */
    fun writeFile(deviceName: String, file: MediaFileInfo, stream: InputStream): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri!!)
            ?: throw IllegalStateException("USB root not accessible")

        val deviceFolder = root.findFile(deviceName)
            ?: root.createDirectory(deviceName)
            ?: throw IllegalStateException("Cannot create device folder '$deviceName'")

        // Skip if already present anywhere in the device folder (flat or subfolder)
        if (findFileAnywhere(deviceFolder, file.displayName) != null) return false

        // Derive date subfolder from capture time (falls back to dateAdded)
        val dateMs = when {
            file.dateTaken > 0 -> file.dateTaken
            file.dateAdded > 0 -> file.dateAdded * 1000L
            else               -> System.currentTimeMillis()
        }
        val dateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMs))
        val dateFolder = deviceFolder.findFile(dateLabel)
            ?: deviceFolder.createDirectory(dateLabel)
            ?: throw IllegalStateException("Cannot create date folder '$dateLabel'")

        // Use "application/octet-stream" so SAF doesn't append a second extension.
        val newFile = dateFolder.createFile("application/octet-stream", file.displayName)
            ?: throw IllegalStateException("Cannot create file '${file.displayName}'")

        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
            stream.copyTo(out)
        } ?: throw IllegalStateException("Cannot open output stream")

        // Stamp EXIF DateTimeOriginal so gallery apps sort by capture date.
        if (file.mimeType.startsWith("image/") && file.mimeType != "image/gif") {
            stampExifDate(newFile.uri, file)
        }

        // Notify MediaStore so the gallery app picks up the new file immediately.
        scanFileForGallery(newFile.uri)

        return true
    }

    /**
     * Converts a SAF document URI to a real filesystem path and asks MediaScanner
     * to index it so gallery apps see the file without needing a manual refresh.
     * Works for external storage volumes (USB OTG drives) where document IDs are
     * formatted as "volumeId:relative/path".
     */
    private fun scanFileForGallery(uri: Uri) {
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) return
            val volumeId = docId.substring(0, colon)
            val relativePath = docId.substring(colon + 1)
            val filePath = "/storage/$volumeId/$relativePath"
            MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
        } catch (_: Exception) { /* best-effort — gallery refresh not critical */ }
    }

    /**
     * Finds a file by [displayName] anywhere under [deviceFolder] —
     * checks the flat root first, then all immediate subfolders (date folders).
     */
    private fun findFileAnywhere(deviceFolder: DocumentFile, displayName: String): DocumentFile? {
        // Check flat root
        deviceFolder.findFile(displayName)?.let { return it }
        // Check date subfolders (one level deep)
        for (child in deviceFolder.listFiles()) {
            if (child.isDirectory) {
                child.findFile(displayName)?.let { return it }
            }
        }
        return null
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
     * Searches both the flat device folder and date subfolders.
     */
    fun readFile(deviceName: String, displayName: String): ByteArray? {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return null) ?: return null
            val folder = root.findFile(deviceName) ?: return null
            val file = findFileAnywhere(folder, displayName) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Overwrites an existing file on the USB drive with [bytes].
     * Used to replace an original image with its compressed version in-place.
     * Searches flat folder and date subfolders.
     * Returns true on success, false if the file doesn't exist or write fails.
     */
    fun overwriteFile(deviceName: String, displayName: String, bytes: ByteArray): Boolean {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return false) ?: return false
            val folder = root.findFile(deviceName) ?: return false
            val file = findFileAnywhere(folder, displayName) ?: return false
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(bytes)
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the set of all filenames under [deviceName]'s folder on the USB drive,
     * scanning both the flat root and all date subfolders (one level deep).
     * Call once per sync session and cache — SAF listFiles() is slow.
     */
    fun getExistingFileNames(deviceName: String): Set<String> {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return emptySet()) ?: return emptySet()
            val folder = root.findFile(deviceName) ?: return emptySet()
            val result = mutableSetOf<String>()
            for (item in folder.listFiles()) {
                when {
                    item.isDirectory -> item.listFiles().forEach { f -> f.name?.let { result.add(it) } }
                    else             -> item.name?.let { result.add(it) }
                }
            }
            result
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

    /**
     * Writes a human-readable integrity manifest to the USB root:
     * "photosync_manifest_[deviceName].json"
     * Lists every filename on the phone, every filename on USB (for this device),
     * and any phone files that are missing from USB.
     */
    fun writeManifest(
        deviceName: String,
        phoneFiles: List<String>,
        usbFiles: Set<String>,
        checkedAtMs: Long
    ) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return) ?: return
            val phoneSet = phoneFiles.toHashSet()
            val missingFromUsb = phoneFiles.filter { name ->
                name !in usbFiles &&
                name.substringBeforeLast('.') !in usbFiles
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(checkedAtMs))
            val json = buildString {
                append("{\n")
                append("  \"checkedAt\": \"$ts\",\n")
                append("  \"deviceName\": \"$deviceName\",\n")
                append("  \"phoneFileCount\": ${phoneFiles.size},\n")
                append("  \"usbFileCount\": ${usbFiles.size},\n")
                append("  \"missingFromUsbCount\": ${missingFromUsb.size},\n")
                append("  \"allPhoneFilesPresent\": ${missingFromUsb.isEmpty()},\n")
                if (missingFromUsb.isNotEmpty()) {
                    append("  \"missingFromUsb\": [\n")
                    missingFromUsb.forEachIndexed { i, name ->
                        append("    \"$name\"")
                        if (i < missingFromUsb.lastIndex) append(",")
                        append("\n")
                    }
                    append("  ],\n")
                } else {
                    append("  \"missingFromUsb\": [],\n")
                }
                append("  \"phoneFiles\": [\n")
                phoneFiles.forEachIndexed { i, name ->
                    append("    \"$name\"")
                    if (i < phoneFiles.lastIndex) append(",")
                    append("\n")
                }
                append("  ]\n")
                append("}")
            }
            val filename = "photosync_manifest_${deviceName.replace(' ', '_')}.json"
            root.findFile(filename)?.delete()
            root.createFile("application/json", filename)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { out ->
                    out.write(json.toByteArray())
                }
            }
        } catch (_: Exception) { /* non-fatal */ }
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

            fun scanFile(file: DocumentFile) {
                val name = file.name ?: return
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in imageExtensions) return
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

            for (item in folder.listFiles()) {
                if (item.isDirectory) item.listFiles().forEach { scanFile(it) }
                else scanFile(item)
            }
        } catch (_: Exception) { /* USB not accessible */ }
        return result
    }

    /**
     * Returns up to [limit] most-recently-modified image/video files across all device folders,
     * sorted newest first. Used by the hub's /hub/files endpoint.
     */
    fun listRecentFiles(limit: Int = 50): List<HubFileEntry> {
        val result = mutableListOf<HubFileEntry>()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return result) ?: return result
            val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "mov")
            for (deviceDir in root.listFiles()) {
                if (!deviceDir.isDirectory) continue
                val deviceName = deviceDir.name ?: continue
                fun collect(f: DocumentFile) {
                    val name = f.name ?: return
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in imageExts) return
                    result += HubFileEntry(deviceName, name, f.length(), f.lastModified())
                }
                for (child in deviceDir.listFiles()) {
                    if (child.isDirectory) child.listFiles().forEach { collect(it) }
                    else collect(child)
                }
            }
        } catch (_: Exception) {}
        result.sortByDescending { it.lastModifiedMs }
        return result.take(limit)
    }

    /**
     * Reads any file from the USB drive by device + display name, searching nested date folders.
     */
    fun readAnyFile(deviceName: String, displayName: String): ByteArray? {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return null) ?: return null
            val folder = root.findFile(deviceName) ?: return null
            val file = findFileAnywhere(folder, displayName) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    /**
     * Returns a scaled-down JPEG thumbnail (longest side ≤ [maxPx]) for the given file,
     * or null if the file can't be found or decoded.
     */
    fun thumbnailForFile(deviceName: String, displayName: String, maxPx: Int = 240): ByteArray? {
        return try {
            val bytes = readAnyFile(deviceName, displayName) ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            val sample = run {
                var s = 1; var w = opts.outWidth; var h = opts.outHeight
                while (w / 2 >= maxPx && h / 2 >= maxPx) { w /= 2; h /= 2; s *= 2 }
                s
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val (w, h) = bmp.width to bmp.height
            val scaled = if (w >= h) {
                val nh = (h.toFloat() / w * maxPx).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, maxPx, nh, true)
            } else {
                val nw = (w.toFloat() / h * maxPx).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, nw, maxPx, true)
            }
            if (scaled !== bmp) bmp.recycle()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            scaled.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    data class HubFileEntry(
        val deviceName: String,
        val displayName: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    private fun loadUri(): Uri? {
        val str = prefs.getString(PREF_USB_URI, null) ?: return null
        return Uri.parse(str)
    }

    companion object {
        const val PREF_USB_URI = "usb_tree_uri"
        const val MANIFEST_FILENAME = "photosync_state.json"   // no leading dot — SAF findFile skips hidden files
    }
}
