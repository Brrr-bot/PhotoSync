package com.photosync.hub.storage

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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

    // Cache for listRecentFiles — scanning 2000+ files via SAF takes 30+ seconds
    @Volatile private var recentFilesCache: List<HubFileEntry> = emptyList()
    @Volatile private var recentFilesCacheTime: Long = 0L
    @Volatile private var cacheRefreshInProgress: Boolean = false
    // URI map built during scan: "deviceName/displayName" -> Uri for instant thumbnail reads
    @Volatile private var fileUriCache: Map<String, Uri> = emptyMap()
    private val CACHE_TTL_MS = 60_000L

    fun invalidateRecentFilesCache() {
        recentFilesCacheTime = 0L
        // Kick off a background refresh so the next request hits the warm cache
        if (!cacheRefreshInProgress) {
            Thread {
                cacheRefreshInProgress = true
                try { refreshRecentFilesCache() } finally { cacheRefreshInProgress = false }
            }.also { it.isDaemon = true; it.name = "hub-cache-refresh" }.start()
        }
    }

    fun warmCache() = refreshRecentFilesCache()

    /** Returns the names of all device folders at the root of the USB drive. */
    fun listDeviceNames(): List<String> {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return emptyList()) ?: return emptyList()
            root.listFiles().filter { it.isDirectory }.mapNotNull { it.name }
        } catch (_: Exception) { emptyList() }
    }

    private fun refreshRecentFilesCache() {
        val result = mutableListOf<HubFileEntry>()
        val uriMap = mutableMapOf<String, Uri>()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return) ?: return
            val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "mov")
            for (deviceDir in root.listFiles()) {
                if (!deviceDir.isDirectory) continue
                val deviceName = deviceDir.name ?: continue
                fun collect(f: DocumentFile) {
                    val name = f.name ?: return
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in imageExts) return
                    // Use the capture date from the filename (most reliable), fall back to
                    // file modification time only when the filename has no parseable date.
                    val dateMs = parseDateFromFilename(name) ?: f.lastModified()
                    result += HubFileEntry(deviceName, name, f.length(), dateMs)
                    uriMap["$deviceName/$name"] = f.uri
                }
                for (child in deviceDir.listFiles()) {
                    if (child.isDirectory) child.listFiles().forEach { collect(it) }
                    else collect(child)
                }
            }
        } catch (_: Exception) {}
        result.sortByDescending { it.lastModifiedMs }
        recentFilesCache = result
        fileUriCache = uriMap
        recentFilesCacheTime = System.currentTimeMillis()
    }

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

        // Skip if already present anywhere in the device folder (flat or subfolder),
        // UNLESS the existing file is zero-bytes or significantly truncated (< 90% of expected
        // size). A truncated file with the right name would otherwise permanently block retries.
        val existingFile = findFileAnywhere(deviceFolder, file.displayName)
        if (existingFile != null) {
            val existingSize = existingFile.length()
            val expectedSize = file.size
            val isTruncated = existingSize == 0L ||
                (expectedSize > 0 && existingSize < (expectedSize * 9 / 10))
            if (!isTruncated) return false   // complete file present — skip
            // Broken/truncated file — delete and re-download
            try { existingFile.delete() } catch (_: Exception) {}
        }

        // Derive date subfolder from capture time.
        // Priority: filename patterns (many) > dateTaken > EXIF from bytes > now.
        // We deliberately do NOT fall back to dateAdded — for compressed copies etc.
        // dateAdded equals the compression/restore date, causing them to land in today's folder.
        val now = System.currentTimeMillis()
        val since2000 = 946_684_800_000L

        // For images: buffer the incoming bytes so we can read EXIF if filename/dateTaken fail.
        // For videos and other types: stream directly (too large to buffer safely).
        val isJpegLike = file.mimeType == "image/jpeg" || file.mimeType == "image/jpg" ||
            file.mimeType == "image/heic" || file.mimeType == "image/heif" || file.mimeType == "image/webp"
        val bufferedBytes: ByteArray? = if (isJpegLike) {
            try { stream.readBytes() } catch (_: Exception) { null }
        } else null

        val fileDateMs: Long? = parseDateFromFilename(file.displayName)
            ?: file.dateTaken.takeIf { it in since2000..now }
            ?: if (bufferedBytes != null) readExifDateFromBytes(bufferedBytes)?.takeIf { it in since2000..now } else null
        val dateMs: Long = fileDateMs ?: now

        val dateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMs))
        val dateFolder = deviceFolder.findFile(dateLabel)
            ?: deviceFolder.createDirectory(dateLabel)
            ?: throw IllegalStateException("Cannot create date folder '$dateLabel'")

        // Use "application/octet-stream" so SAF doesn't append a second extension.
        val newFile = dateFolder.createFile("application/octet-stream", file.displayName)
            ?: throw IllegalStateException("Cannot create file '${file.displayName}'")

        // Write atomically: if the stream copy fails partway, delete the partial file so it is
        // NOT mistaken for a complete backup on the next sync. Without this, a truncated file
        // stays on USB with the right name; getExistingFileNames sees it; future syncs skip it
        // permanently, leaving a broken file that can never be retried.
        try {
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                if (bufferedBytes != null) out.write(bufferedBytes)
                else stream.copyTo(out)
            } ?: run {
                try { newFile.delete() } catch (_: Exception) {}
                throw IllegalStateException("Cannot open output stream")
            }
        } catch (e: Exception) {
            // Best-effort cleanup — if delete fails the next sync will still re-download because
            // the file size on USB (0 or partial) won't match what the phone serves, but at
            // minimum we try to remove the incomplete entry so dedup doesn't treat it as done.
            try { newFile.delete() } catch (_: Exception) {}
            throw e
        }

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
     * [DateTime] tags only when they are absent or zero.
     *
     * Date source priority (most reliable first):
     *   1. Filename pattern YYYYMMDD_HHMMSS (e.g. "20250905_165736.jpg" or "20250905_165736 (1).jpg")
     *   2. [MediaFileInfo.dateTaken] (MediaStore DATE_TAKEN, from EXIF on the phone)
     *   3. Skip — dateAdded is unreliable (equals the time the file entered MediaStore, which for
     *      compressed copies is the compression date, not the capture date)
     *
     * Silently no-ops if the format doesn't support EXIF (e.g. PNG).
     */
    private fun stampExifDate(uri: Uri, file: MediaFileInfo) {
        try {
            val now = System.currentTimeMillis()
            val epochMs = parseDateFromFilename(file.displayName)
                ?: file.dateTaken.takeIf { it > 0 && it <= now }
                ?: return   // no reliable date — don't touch the file
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
     * Parses epoch-ms from a filename, trying multiple patterns in priority order.
     * All parsers use isLenient=false so invalid month/day values throw rather than rolling over.
     * Any result in the future (or before 2000) is rejected.
     *
     * Patterns tried (most specific first):
     *  1. YYYYMMDD_HHMMSS  — standard Samsung/Android: IMG_20220606_141510.jpg
     *  2. YYYYMMDD-HHMMSS  — dash variant: 20220606-141510.jpg
     *  3. YYYY-MM-DD-HH-MM-SS — Signal/fully-hyphenated: signal-2022-06-06-14-15-10.jpg
     *  4. YYYY-MM-DD_HH-MM-SS — mixed: photo_2022-06-06_14-15-10.jpg
     *  5. YYYYMMDD only     — WhatsApp/date-only: IMG-20220606-WA0001.jpg → midnight of that day
     *  6. YYYY-MM-DD only   — ISO date in name: 2022-06-06.jpg → midnight
     */
    private fun parseDateFromFilename(name: String): Long? {
        val now = System.currentTimeMillis()
        val since2000 = 946_684_800_000L   // 2000-01-01 UTC
        val stem = name.substringBeforeLast('.')

        fun tryMs(ms: Long): Long? = if (ms in since2000..now) ms else null

        // 1. YYYYMMDD_HHMMSS
        Regex("""(\d{8})_(\d{6})""").find(stem)?.let { m ->
            try {
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }
                sdf.parse("${m.groupValues[1]}_${m.groupValues[2]}")?.time?.let { tryMs(it) }?.let { return it }
            } catch (_: Exception) {}
        }

        // 2. YYYYMMDD-HHMMSS
        Regex("""(\d{8})-(\d{6})(?!\d)""").find(stem)?.let { m ->
            try {
                val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { isLenient = false }
                sdf.parse("${m.groupValues[1]}-${m.groupValues[2]}")?.time?.let { tryMs(it) }?.let { return it }
            } catch (_: Exception) {}
        }

        // 3. YYYY-MM-DD-HH-MM-SS  (signal-2022-06-06-14-15-10)
        Regex("""(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""").find(stem)?.let { m ->
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).apply { isLenient = false }
                val raw = m.groupValues.drop(1).joinToString("-")
                sdf.parse(raw)?.time?.let { tryMs(it) }?.let { return it }
            } catch (_: Exception) {}
        }

        // 4. YYYY-MM-DD_HH-MM-SS
        Regex("""(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})""").find(stem)?.let { m ->
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply { isLenient = false }
                sdf.parse("${m.groupValues[1]}_${m.groupValues[2]}")?.time?.let { tryMs(it) }?.let { return it }
            } catch (_: Exception) {}
        }

        // 5. YYYYMMDD only (date-only, e.g. WhatsApp IMG-20220606-WA0001.jpg)
        Regex("""(\d{8})""").find(stem)?.let { m ->
            try {
                val sdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }
                sdf.parse(m.groupValues[1])?.time?.let { tryMs(it) }?.let { return it }
            } catch (_: Exception) {}
        }

        // 6. YYYY-MM-DD only
        Regex("""(\d{4}-\d{2}-\d{2})""").find(stem)?.let { m ->
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                sdf.parse(m.groupValues[1])?.time?.let { tryMs(it) }?.let { return it }
            } catch (_: Exception) {}
        }

        // 7. 13-digit epoch-ms embedded in filename (WeChat mmexport, Android download IDs)
        //    e.g. mmexport1729837012822.mp4, 1746777749456.mp4
        Regex("""(\d{13})""").find(stem)?.let { m ->
            val ms = m.groupValues[1].toLongOrNull() ?: return@let
            tryMs(ms)?.let { return it }
        }

        return null
    }

    /**
     * Reads EXIF DateTimeOriginal (or DateTime) from raw image bytes.
     * Returns epoch-ms, or null if absent / unreadable / out of valid range.
     */
    private fun readExifDateFromBytes(bytes: ByteArray): Long? {
        return try {
            val exif = ExifInterface(bytes.inputStream())
            val tag = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return null
            if (tag.isBlank() || tag.startsWith("0000")) return null
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }
                .parse(tag)?.time
        } catch (_: Exception) { null }
    }

    /**
     * One-time repair pass: scans all image files on USB under [deviceName] and
     * re-stamps EXIF DateTimeOriginal from the filename wherever the stored EXIF date
     * differs from the filename date by more than 1 hour.
     *
     * This fixes files whose EXIF was previously stamped with the sync/compression date
     * instead of the actual capture date (e.g. "(1)" compressed copies from the old
     * compression loop had dateTaken=0, so stampExifDate fell back to dateAdded which
     * was the compression date, not the photo date).
     *
     * Returns the count of files repaired.
     */
    fun repairExifFromFilenames(deviceName: String): Int {
        var repaired = 0
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return 0) ?: return 0
            val folder = root.findFile(deviceName) ?: return 0
            val imageExts = setOf("jpg", "jpeg", "webp")

            val now = System.currentTimeMillis()
            fun repairFile(file: DocumentFile) {
                val name = file.name ?: return
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in imageExts) return
                val correctMs = parseDateFromFilename(name)  // null if no valid date in filename
                try {
                    context.contentResolver.openFileDescriptor(file.uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        val existing = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                        val existingMs = if (!existing.isNullOrBlank() && !existing.startsWith("0000")) {
                            try { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(existing)?.time ?: 0L }
                            catch (_: Exception) { 0L }
                        } else 0L

                        if (correctMs == null) {
                            // No parseable filename date — if EXIF is in the future, clear it
                            // (was stamped with a bad date by the old lenient-parser repair pass)
                            if (existingMs > now) {
                                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
                                exif.setAttribute(ExifInterface.TAG_DATETIME, null)
                                exif.saveAttributes()
                                repaired++
                            }
                        } else {
                            // Only fix if the stored date is wrong by more than 1 hour
                            if (existingMs == 0L || kotlin.math.abs(existingMs - correctMs) > 3_600_000L) {
                                val correctFormatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                                    .format(Date(correctMs))
                                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, correctFormatted)
                                exif.setAttribute(ExifInterface.TAG_DATETIME, correctFormatted)
                                exif.saveAttributes()
                                repaired++
                            }
                        }
                    }
                } catch (_: Exception) { /* corrupt EXIF or unsupported format — skip */ }
            }

            for (item in folder.listFiles()) {
                if (item.isDirectory) item.listFiles().forEach { repairFile(it) }
                else repairFile(item)
            }
        } catch (_: Exception) { /* USB not accessible */ }
        return repaired
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
     * Opens a file for streaming (no full read into memory) — returns the InputStream and its
     * byte length, or null if not found. Use this to serve large files (videos) without OOM.
     * Caller owns the stream and must close it.
     */
    fun openFileStream(deviceName: String, displayName: String): Pair<InputStream, Long>? {
        return try {
            // Fast path: use the URI built during the warm scan. SAF findFileAnywhere scans every
            // date subfolder (hundreds) which takes many seconds per file — far too slow when a
            // client restore pulls hundreds of files and times out. The cache makes it instant.
            fileUriCache["$deviceName/$displayName"]?.let { uri ->
                runCatching {
                    val len = DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L
                    if (len > 0) {
                        val stream = context.contentResolver.openInputStream(uri)
                        if (stream != null) return Pair(stream, len)
                    }
                }
            }
            // Slow path: SAF scan (cache miss — file added since last scan, or cache cold).
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return null) ?: return null
            val folder = root.findFile(deviceName) ?: return null
            val file = findFileAnywhere(folder, displayName) ?: return null
            val len = file.length()
            val stream = context.contentResolver.openInputStream(file.uri) ?: return null
            Pair(stream, len)
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

    /**
     * Deletes every file at [root] whose name is [filename] OR a MediaStore-renamed variant of it
     * ("photosync_state (1).json", "photosync_state (2).json", …). Used before (re)writing the
     * manifest/state files so SAF never auto-renames a fresh write into an endless "(N)" series.
     */
    private fun deleteNamedVariants(root: DocumentFile, filename: String) {
        val dot  = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext  = if (dot > 0) filename.substring(dot) else ""
        val variant = Regex("""^${Regex.escape(stem)}(\s*\(\d+\))*${Regex.escape(ext)}$""")
        for (f in root.listFiles()) {
            val n = f.name ?: continue
            if (n == filename || variant.matches(n)) {
                try { f.delete() } catch (_: Exception) {}
            }
        }
    }

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
            deleteNamedVariants(root, filename)
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

            // Delete old file and write fresh (SAF can't seek/truncate). Purge any "(N)" variants
            // too so a stale copy can't make SAF auto-rename this write into an endless series.
            deleteNamedVariants(root, MANIFEST_FILENAME)
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
     * Moves files that are in the wrong date subfolder into the correct one derived from their
     * filename (YYYYMMDD_HHMMSS pattern). A file is "wrong" if the parent folder date differs
     * from the filename date by more than 1 day.
     *
     * This fixes files that were written when dateTaken=0 caused the old code to fall back to
     * dateAdded (= the compression/sync date) for folder selection, landing everything in a
     * single date folder (e.g. 2026-06-06) instead of the correct capture-date folders.
     *
     * SAF has no rename/move — we create a new file in the correct folder, copy bytes, then
     * delete the original. Returns the count of files moved.
     */
    fun reorganizeMisplacedFiles(deviceName: String): Int {
        var moved = 0
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return 0) ?: return 0
            val deviceFolder = root.findFile(deviceName) ?: return 0
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dayMs = 86_400_000L

            // Collect (file, correctDateLabel) pairs where file is in wrong folder
            val toMove = mutableListOf<Pair<DocumentFile, String>>()
            for (child in deviceFolder.listFiles()) {
                if (!child.isDirectory) continue
                val folderLabel = child.name ?: continue   // e.g. "2026-06-06"
                val folderMs = try { dateFormat.parse(folderLabel)?.time ?: continue } catch (_: Exception) { continue }
                for (file in child.listFiles()) {
                    val name = file.name ?: continue
                    val correctMs = parseDateFromFilename(name) ?: continue
                    if (kotlin.math.abs(correctMs - folderMs) > dayMs) {
                        val correctLabel = dateFormat.format(Date(correctMs))
                        toMove.add(file to correctLabel)
                    }
                }
            }

            for ((srcFile, targetLabel) in toMove) {
                val name = srcFile.name ?: continue
                try {
                    // Create or find the target date folder
                    val targetFolder = deviceFolder.findFile(targetLabel)
                        ?: deviceFolder.createDirectory(targetLabel)
                        ?: continue

                    // Skip if already exists in target (avoid duplicate)
                    if (targetFolder.findFile(name) != null) {
                        srcFile.delete(); continue
                    }

                    val destFile = targetFolder.createFile("application/octet-stream", name) ?: continue

                    // Copy bytes
                    var copyOk = false
                    try {
                        context.contentResolver.openInputStream(srcFile.uri)?.use { inp ->
                            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                                inp.copyTo(out)
                                copyOk = true
                            }
                        }
                    } catch (_: Exception) {}

                    if (copyOk) {
                        srcFile.delete()
                        moved++
                    } else {
                        // Copy failed — remove the incomplete destination
                        try { destFile.delete() } catch (_: Exception) {}
                    }
                } catch (_: Exception) { /* skip this file */ }
            }

            // Second pass: rescue files stranded in clearly-wrong-year folders
            // (year < 1900 or > 2100) that have no parseable Samsung filename date.
            // These were created by the old lenient-SimpleDateFormat bug where epoch-
            // timestamp filenames like "IMG_1755589224045_1755656772686.jpg" caused
            // dates in year 7155, 8220, 2727, etc.
            for (child in deviceFolder.listFiles()) {
                if (!child.isDirectory) continue
                val folderLabel = child.name ?: continue
                val folderMs = try { dateFormat.parse(folderLabel)?.time ?: continue }
                               catch (_: Exception) { continue }
                val folderCal = java.util.Calendar.getInstance().apply { timeInMillis = folderMs }
                val folderYear = folderCal.get(java.util.Calendar.YEAR)
                if (folderYear in 1900..2100) continue   // normal folder — skip

                // Bad year folder — move every file to flat root and clear bad EXIF
                for (file in child.listFiles()) {
                    val name = file.name ?: continue
                    // If there's already a copy in the flat root, just delete the stray
                    if (deviceFolder.findFile(name) != null) { try { file.delete() } catch (_: Exception) {}; continue }
                    val destFile = deviceFolder.createFile("application/octet-stream", name) ?: continue
                    var copyOk = false
                    try {
                        context.contentResolver.openInputStream(file.uri)?.use { inp ->
                            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                                inp.copyTo(out); copyOk = true
                            }
                        }
                    } catch (_: Exception) {}
                    if (copyOk) {
                        // Clear EXIF date — it was stamped with the same bad future date
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in setOf("jpg", "jpeg", "webp")) {
                            try {
                                context.contentResolver.openFileDescriptor(destFile.uri, "rw")?.use { pfd ->
                                    val exif = ExifInterface(pfd.fileDescriptor)
                                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
                                    exif.setAttribute(ExifInterface.TAG_DATETIME, null)
                                    exif.saveAttributes()
                                }
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                        try { file.delete() } catch (_: Exception) {}
                        moved++
                    } else {
                        try { destFile.delete() } catch (_: Exception) {}
                    }
                }
            }

            // Third pass: organize flat-root files (directly in deviceFolder, not in any subfolder).
            // Date priority:
            //   1. Filename date (always reliable)
            //   2. EXIF DateTimeOriginal — but ONLY if the date is >7 days ago. The hub's
            //      writeFile stamped EXIF with the sync date for files whose filenames had no
            //      parseable date; since sync happened recently, any EXIF date within the last
            //      7 days is likely a fake sync-date and must be ignored.
            //   3. No reliable date → leave in flat root
            val imageExtsFlat = setOf("jpg", "jpeg", "webp", "heic", "heif", "png")
            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L
            for (child in deviceFolder.listFiles()) {
                if (child.isDirectory) continue
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                val now2 = System.currentTimeMillis()
                val correctMs: Long = parseDateFromFilename(name) ?: run {
                    if (ext !in imageExtsFlat) return@run null
                    try {
                        context.contentResolver.openFileDescriptor(child.uri, "r")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)
                            val tag = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                                ?: return@use null
                            if (tag.isBlank() || tag.startsWith("0000")) return@use null
                            val ms = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                                .apply { isLenient = false }.parse(tag)?.time ?: return@use null
                            // Only trust EXIF if the date is older than 7 days — recent dates
                            // are likely the sync-stamp fake date, not the real capture date.
                            if (ms in 946_684_800_000L..(now2 - sevenDaysMs)) ms else null
                        }
                    } catch (_: Exception) { null }
                } ?: continue

                val targetLabel = dateFormat.format(Date(correctMs))
                try {
                    val targetFolder = deviceFolder.findFile(targetLabel)
                        ?: deviceFolder.createDirectory(targetLabel)
                        ?: continue
                    if (targetFolder.findFile(name) != null) {
                        child.delete(); continue
                    }
                    val destFile = targetFolder.createFile("application/octet-stream", name) ?: continue
                    var copyOk = false
                    try {
                        context.contentResolver.openInputStream(child.uri)?.use { inp ->
                            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                                inp.copyTo(out); copyOk = true
                            }
                        }
                    } catch (_: Exception) {}
                    if (copyOk) {
                        child.delete(); moved++
                    } else {
                        try { destFile.delete() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // Remove any now-empty date folders we moved files out of
            for (child in deviceFolder.listFiles()) {
                if (child.isDirectory && child.listFiles().isEmpty()) {
                    try { child.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { /* USB not accessible */ }
        return moved
    }

    /**
     * Removes duplicate files within [deviceName]'s folder. Two files are duplicates of the same
     * logical photo/video when their names match after stripping MediaStore's auto-rename suffixes
     * (" (1)", " (1) (1)", " (2)", …) AND they share the same extension — e.g. "photo.jpg",
     * "photo (1).jpg" and "photo (1) (1).jpg" are one family. These accumulate when the phone
     * serves a re-named copy (compressed → "(1)") that the hub then backs up as a "new" file, and
     * from the folder-reorg passes leaving the same name in two folders.
     *
     * The hub is the pristine backup store, so for each family we KEEP THE LARGEST copy (the full
     * original) and delete the smaller/duplicate copies. Returns the number of files deleted.
     */
    fun removeDuplicateFiles(deviceName: String): Int {
        var deleted = 0
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return 0) ?: return 0
            val folder = root.findFile(deviceName) ?: return 0

            // Strips a filename to its family key: base stem (all trailing " (N)" groups removed)
            // plus the lowercased extension, so suffixed copies group with their original.
            fun familyKey(name: String): String {
                val dot = name.lastIndexOf('.')
                var stem = if (dot > 0) name.substring(0, dot) else name
                val ext  = if (dot > 0) name.substring(dot).lowercase() else ""
                val re = Regex("""\s*\(\d+\)$""")
                var prev: String
                do { prev = stem; stem = stem.replace(re, "") } while (prev != stem)
                return "$stem$ext"
            }

            // Group every file (flat + one level of subfolders) by family key.
            val byFamily = mutableMapOf<String, MutableList<DocumentFile>>()
            fun add(f: DocumentFile) {
                if (f.isDirectory) return
                val name = f.name ?: return
                byFamily.getOrPut(familyKey(name)) { mutableListOf() }.add(f)
            }
            for (item in folder.listFiles()) {
                if (item.isDirectory) item.listFiles().forEach { add(it) }
                else add(item)
            }

            for ((_, copies) in byFamily) {
                if (copies.size < 2) continue
                // Keep the largest copy (the full original backup); delete the rest.
                val keep = copies.maxByOrNull { it.length() } ?: continue
                for (dup in copies) {
                    if (dup.uri == keep.uri) continue
                    try { if (dup.delete()) deleted++ } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { /* USB not accessible */ }
        return deleted
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
     * sorted newest first. Result is cached for 60 s — SAF scanning 2000+ files takes 30+ s.
     * Call [invalidateRecentFilesCache] after a sync to force a fresh scan on next request.
     */
    fun listRecentFiles(limit: Int = 50): List<HubFileEntry> {
        val now = System.currentTimeMillis()
        val cacheStale = now - recentFilesCacheTime >= CACHE_TTL_MS
        // Always return cached data immediately (stale-while-revalidate)
        if (cacheStale && !cacheRefreshInProgress) {
            Thread {
                cacheRefreshInProgress = true
                try { refreshRecentFilesCache() } finally { cacheRefreshInProgress = false }
            }.also { it.isDaemon = true; it.name = "hub-cache-refresh" }.start()
        }
        return recentFilesCache.take(limit)
    }

    /**
     * Reads any file from the USB drive by device + display name, searching nested date folders.
     */
    fun readAnyFile(deviceName: String, displayName: String): ByteArray? {
        return try {
            // Fast path: use URI from scan cache if available
            val cachedUri = fileUriCache["$deviceName/$displayName"]
            if (cachedUri != null) {
                return context.contentResolver.openInputStream(cachedUri)?.use { it.readBytes() }
            }
            // Slow path: SAF scan (used before cache is warm)
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return null) ?: return null
            val folder = root.findFile(deviceName) ?: return null
            val file = findFileAnywhere(folder, displayName) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    /**
     * Deletes a file from the USB drive by device name and display name.
     * Searches flat folder and date subfolders (one level deep).
     * Returns true if the file was found and deleted.
     */
    fun deleteFile(deviceName: String, displayName: String): Boolean {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri ?: return false) ?: return false
            val folder = root.findFile(deviceName) ?: return false
            val file = findFileAnywhere(folder, displayName) ?: return false
            val deleted = file.delete()
            if (deleted) {
                // Update in-memory caches so the deleted file stops appearing immediately
                fileUriCache = fileUriCache - "$deviceName/$displayName"
                recentFilesCache = recentFilesCache.filter {
                    it.deviceName != deviceName || it.displayName != displayName
                }
            }
            deleted
        } catch (_: Exception) { false }
    }

    /**
     * Returns a scaled-down JPEG thumbnail (longest side ≤ [maxPx]) for the given file,
     * or null if the file can't be found or decoded.
     * For video files (.mp4 / .mov) uses MediaMetadataRetriever to extract a frame.
     */
    fun thumbnailForFile(deviceName: String, displayName: String, maxPx: Int = 240): ByteArray? {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext == "mp4" || ext == "mov") return videoThumbnailForFile(deviceName, displayName, maxPx)
        return try {
            val bytes = readAnyFile(deviceName, displayName) ?: return null
            // Read EXIF orientation before decoding
            val orientation = bytes.inputStream().use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else                                 -> 0f
            }
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
            var scaled = if (w >= h) {
                val nh = (h.toFloat() / w * maxPx).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, maxPx, nh, true)
            } else {
                val nw = (w.toFloat() / h * maxPx).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, nw, maxPx, true)
            }
            if (scaled !== bmp) bmp.recycle()
            if (rotation != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
                scaled.recycle()
                scaled = rotated
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            scaled.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    /** Extracts a frame from a video file on USB and returns it as a scaled JPEG thumbnail. */
    private fun videoThumbnailForFile(deviceName: String, displayName: String, maxPx: Int): ByteArray? {
        return try {
            val uri = fileUriCache["$deviceName/$displayName"] ?: run {
                val root = DocumentFile.fromTreeUri(context, treeUri ?: return null) ?: return null
                val folder = root.findFile(deviceName) ?: return null
                findFileAnywhere(folder, displayName)?.uri ?: return null
            }
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000L
                val frameUs = if (durationUs > 2_000_000L) 1_000_000L else 0L
                val frame = mmr.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.frameAtTime ?: return null
                val w = frame.width; val h = frame.height
                if (w <= 0 || h <= 0) { frame.recycle(); return null }
                val scaled = if (w >= h) {
                    val nh = (h.toFloat() / w * maxPx).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(frame, maxPx, nh, true)
                } else {
                    val nw = (w.toFloat() / h * maxPx).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(frame, nw, maxPx, true)
                }
                if (scaled !== frame) frame.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                scaled.recycle()
                out.toByteArray()
            } finally { try { mmr.release() } catch (_: Throwable) {} }
        } catch (_: Throwable) { null }
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
        // Matches YYYYMMDD_HHMMSS anywhere in a filename (handles plain, (1) suffix, Screenshot_ prefix, etc.)
        // FILENAME_DATE_RE removed — parseDateFromFilename now tries multiple patterns inline
    }
}
