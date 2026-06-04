package com.photosync.hub.service

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photosync.hub.compress.MediaCompressor
import com.photosync.hub.storage.SyncStateRepository
import com.photosync.hub.storage.UsbStorageManager
import com.photosync.hub.util.RemoteLogger
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import com.photosync.shared.model.DateCorrection
import com.photosync.shared.model.MediaFileInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class FileSyncer(
    private val context: Context,
    private val usbStorage: UsbStorageManager,
    private val syncState: SyncStateRepository,
    private val onProgress: (message: String) -> Unit,
    private val onTransferProgress: ((current: Int, total: Int, filename: String, fileSizeBytes: Long, sessionRemainingBytes: Long) -> Unit)? = null,
    private val onFileBytes: ((bytesRead: Long, fileTotal: Long, filename: String) -> Unit)? = null
) {

    private val gson = Gson()

    // Long timeout for file downloads (large video files)
    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.FILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // Short timeout for /replace — compressed JPEGs are small; fail fast if phone disconnected
    private val replaceClient = OkHttpClient.Builder()
        .connectTimeout(Constants.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val myDeviceName: String = Build.MODEL

    // Per-device lock to prevent two concurrent sync passes (LAN + Tailscale
    // handshakes can both trigger syncs within the same second). Concurrent
    // passes race on the compressed-files set and cause duplicate /replace calls.
    private val deviceLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun syncDevice(clientInfo: ClientInfo): Int {
        val lock = deviceLocks.getOrPut(clientInfo.deviceName) { Any() }
        synchronized(lock) {
            return syncDeviceLocked(clientInfo)
        }
    }

    private fun syncDeviceLocked(clientInfo: ClientInfo): Int {

        // ── Date repair pass (runs first, skips already-checked files) ─────────
        // Hub reads EXIF dates from USB files and corrects DATE_TAKEN on the phone
        // for any files where the MediaStore value is wrong or missing.
        val usbExifDates = usbStorage.getAllExifDates(clientInfo.deviceName)
        if (usbExifDates.isNotEmpty()) {
            val alreadyChecked = syncState.getDateCheckedFiles(clientInfo.deviceName)
            val unchecked = usbExifDates.filterKeys { it !in alreadyChecked }
            if (unchecked.isNotEmpty()) {
                onProgress("Checking dates for ${unchecked.size} file(s)…")
                val allPhoneFiles = fetchFileList(clientInfo.ip, 0L)
                if (allPhoneFiles != null) {
                    val phoneByName = allPhoneFiles.associateBy { it.displayName }
                    val corrections = unchecked.mapNotNull { (name, usbDate) ->
                        val p = phoneByName[name] ?: return@mapNotNull null
                        if (p.dateTaken == 0L || kotlin.math.abs(p.dateTaken - usbDate) > 1_000L)
                            DateCorrection(name, usbDate) else null
                    }
                    if (corrections.isNotEmpty()) {
                        val fixed = postFixDates(clientInfo.ip, corrections)
                        onProgress("Corrected dates on $fixed/${corrections.size} file(s)")
                    }
                }
                // Mark all unchecked files as checked — skip them on future syncs
                syncState.markDateChecked(clientInfo.deviceName, unchecked.keys.toSet())
                onProgress("Date check complete — ${unchecked.size} file(s) reviewed")
            }
        }
        // ── Normal sync ────────────────────────────────────────────────────────

        val lastSync = syncState.getLastSync(clientInfo.mac)
        val existing = usbStorage.getExistingFileNames(clientInfo.deviceName)
        val alreadyCompressed = syncState.getCompressedFiles(clientInfo.deviceName).toMutableSet()

        // Build a set of lowercase stems for every VIDEO on USB (e.g. "vid_20231201_143210"
        // from "VID_20231201_143210.mp4").  Any phone image whose stem matches one of these
        // is a VideoSpaceManager poster — the JPEG thumbnail that replaced the video locally.
        // We must NEVER upload posters to USB (the full video is already there) and must
        // NEVER compress-and-replace them back to the phone, or we get an infinite loop.
        val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "3gp", "webm", "m4v")
        val usbVideoStems = existing
            .filter { it.substringAfterLast('.').lowercase() in videoExtensions }
            .map    { it.substringBeforeLast('.').lowercase() }
            .toHashSet()

        fun isPosterImage(displayName: String): Boolean {
            if (usbVideoStems.isEmpty()) return false
            val ext = displayName.substringAfterLast('.').lowercase()
            if (ext !in setOf("jpg", "jpeg", "webp", "png")) return false
            return displayName.substringBeforeLast('.').lowercase() in usbVideoStems
        }

        // Always fetch the complete file list from the phone (since=1 so client treats
        // this as a download scan, not a compression scan).  The list is just metadata
        // (filenames / sizes — no bytes), so fetching all files every cycle is cheap.
        // USB presence is the sole truth for what's backed up; no SharedPreferences
        // cache can drift out of sync with the drive.
        val allFiles = fetchFileList(clientInfo.ip, 1L) ?: return 0

        // ── Write manifest to USB so the user can always verify integrity ──────
        usbStorage.writeManifest(
            deviceName   = clientInfo.deviceName,
            phoneFiles   = allFiles.map { it.displayName },
            usbFiles     = existing,
            checkedAtMs  = System.currentTimeMillis()
        )

        // ── Integrity check: find every phone file not yet on USB ─────────────
        val toProcess = allFiles.filter { file ->
            val strippedName = stripReplacementSuffixes(file.displayName)
            !isPosterImage(file.displayName) &&
            file.displayName !in existing &&
            file.displayName.substringBeforeLast('.') !in existing &&
            strippedName !in existing
        }

        if (toProcess.isNotEmpty()) {
            onProgress("⚠ ${clientInfo.deviceName}: ${toProcess.size} file(s) missing from USB — syncing now")
        }

        var synced = 0
        var latestTimestamp = lastSync
        val totalSessionBytes = toProcess.sumOf { it.size }
        var sessionBytesCompleted = 0L

        if (toProcess.isEmpty()) {
            val newestTs = allFiles.maxOfOrNull { it.dateAdded * 1000 } ?: lastSync
            if (newestTs > lastSync) syncState.updateLastSync(clientInfo.mac, newestTs)
            usbStorage.invalidateRecentFilesCache()
            onProgress("✓ ${clientInfo.deviceName}: all ${allFiles.size} phone file(s) confirmed on USB (${existing.size} total files on drive)")
        } else {
            onProgress("${clientInfo.deviceName}: ${toProcess.size} new file(s) to sync…")

            for ((index, file) in toProcess.withIndex()) {
                try {
                    val remaining = totalSessionBytes - sessionBytesCompleted
                    onTransferProgress?.invoke(
                        index + 1, toProcess.size, file.displayName, file.size, remaining
                    )
                    onProgress("⬇ ${index + 1}/${toProcess.size}: ${file.displayName} (${file.size / 1024}KB)")

                    // Backup only — stream every file straight to USB. No hub-side compression:
                    // the hub is lossy JPEG, so compressing here then re-compressing to WebP on
                    // the phone would lose quality twice. The phone does the single WebP pass.
                    // dl_index/dl_total drive the phone's upload-progress card.
                    downloadStream(clientInfo.ip, file.id, clientInfo.deviceName, file,
                        index + 1, toProcess.size)
                    onProgress("✓ ${index + 1}/${toProcess.size}: ${file.displayName} saved to USB")

                    syncState.markDownloaded(clientInfo.deviceName, file.id)
                    synced++
                    sessionBytesCompleted += file.size
                    val fileMs = file.dateAdded * 1000
                    if (fileMs > latestTimestamp) latestTimestamp = fileMs

                } catch (e: java.net.ConnectException) {
                    onProgress("✗ Client unreachable — stopping sync")
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    onProgress("✗ Client timed out — stopping sync")
                    break
                } catch (e: Exception) {
                    onProgress("✗ ${file.displayName}: ${e.message}")
                }
            }

            if (synced > 0) {
                syncState.updateLastSync(clientInfo.mac, latestTimestamp)
                usbStorage.invalidateRecentFilesCache()
            }
            onProgress("${clientInfo.deviceName}: $synced/${toProcess.size} files synced")
        }

        // Compression is done client-side (WebP on the phone after backup is confirmed).
        // The hub is lossy-JPEG so compressing here then re-compressing on the client
        // would cause double quality loss. Hub job: backup only.
        return synced
    }

    private fun fetchFileList(ip: String, sinceSeconds: Long): List<MediaFileInfo>? {
        return try {
            val timestamp = System.currentTimeMillis()
            val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
            val signature = HmacAuth.sign(payload)

            val request = Request.Builder()
                .url("http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_MEDIA_LIST}?since=$sinceSeconds")
                .get()
                .addHeader(Constants.HEADER_HMAC, signature)
                .addHeader(Constants.HEADER_TIMESTAMP, timestamp.toString())
                .addHeader(Constants.HEADER_DEVICE, myDeviceName)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onProgress("ERROR fetching file list from $ip: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val type = object : TypeToken<List<MediaFileInfo>>() {}.type
                gson.fromJson(body, type)
            }
        } catch (e: Exception) {
            onProgress("ERROR fetching file list from $ip: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Streams a file directly to USB — never loads it fully into memory.
     *  dlIndex/dlTotal are forwarded to the phone so its upload card shows real progress. */
    private fun downloadStream(ip: String, id: Long, deviceName: String, file: MediaFileInfo,
                               dlIndex: Int = 0, dlTotal: Int = 0) {
        val timestamp = System.currentTimeMillis()
        val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
        val signature = HmacAuth.sign(payload)

        val urlBuilder = StringBuilder("http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_MEDIA_FILE}?id=$id")
        if (dlTotal > 0) urlBuilder.append("&dl_index=$dlIndex&dl_total=$dlTotal")
        val request = Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader(Constants.HEADER_HMAC, signature)
            .addHeader(Constants.HEADER_TIMESTAMP, timestamp.toString())
            .addHeader(Constants.HEADER_DEVICE, myDeviceName)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val bodyStream = response.body?.byteStream() ?: throw Exception("empty body")
            val fileSize = file.size
            val countingStream = object : InputStream() {
                private var totalRead = 0L
                private var lastReported = -256 * 1024L   // forces first callback on very first read
                private val interval = 256 * 1024L

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = bodyStream.read(b, off, len)
                    if (n > 0) {
                        totalRead += n
                        if (totalRead >= fileSize || totalRead - lastReported >= interval) {
                            lastReported = totalRead
                            onFileBytes?.invoke(totalRead, fileSize, file.displayName)
                        }
                    }
                    return n
                }
                override fun read(): Int {
                    val b = bodyStream.read()
                    if (b >= 0) {
                        totalRead++
                        if (totalRead >= fileSize || totalRead - lastReported >= interval) {
                            lastReported = totalRead
                            onFileBytes?.invoke(totalRead, fileSize, file.displayName)
                        }
                    }
                    return b
                }
                override fun close() = bodyStream.close()
            }
            usbStorage.writeFile(deviceName, file, countingStream)
        }
    }

    /** Downloads file bytes into memory. Use for images only (needed for compression). */
    private fun downloadBytes(ip: String, id: Long): ByteArray {
        val timestamp = System.currentTimeMillis()
        val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
        val signature = HmacAuth.sign(payload)

        val request = Request.Builder()
            .url("http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_MEDIA_FILE}?id=$id")
            .get()
            .addHeader(Constants.HEADER_HMAC, signature)
            .addHeader(Constants.HEADER_TIMESTAMP, timestamp.toString())
            .addHeader(Constants.HEADER_DEVICE, myDeviceName)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RemoteLogger.e("downloadBytes id=$id HTTP ${response.code}")
                    throw Exception("HTTP ${response.code}")
                }
                return response.body?.bytes() ?: run {
                    RemoteLogger.e("downloadBytes id=$id empty body")
                    throw Exception("empty body")
                }
            }
        } catch (e: Exception) {
            RemoteLogger.e("downloadBytes id=$id failed: ${e.message}")
            throw e
        }
    }

    /** POSTs compressed bytes to /replace on the client. Returns true on success. */
    private fun postReplace(ip: String, id: Long, mime: String, bytes: ByteArray, dateTaken: Long = 0L,
                            batchIndex: Int = 0, batchTotal: Int = 0): Boolean {
        return try {
            val timestamp = System.currentTimeMillis()
            val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
            val signature = HmacAuth.sign(payload)

            val urlBuilder = StringBuilder(
                "http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_REPLACE}?id=$id&mime=${mime.replace("/", "%2F")}"
            )
            if (dateTaken > 0L) urlBuilder.append("&${Constants.PARAM_DATE_TAKEN}=$dateTaken")
            if (batchTotal > 0) urlBuilder.append("&batch_index=$batchIndex&batch_total=$batchTotal")

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .post(bytes.toRequestBody(mime.toMediaType()))
                .addHeader(Constants.HEADER_HMAC, signature)
                .addHeader(Constants.HEADER_TIMESTAMP, timestamp.toString())
                .addHeader(Constants.HEADER_DEVICE, myDeviceName)
                .build()

            replaceClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string() }.getOrNull() ?: ""
                    onProgress("Replace HTTP ${response.code} id=$id: $body")
                }
                response.isSuccessful
            }
        } catch (e: java.net.ConnectException) {
            // Phone is unreachable — rethrow so the caller can stop the loop
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw e
        } catch (e: Exception) {
            onProgress("Replace error id=$id: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    companion object {
        /** Bump this to force a one-time clear of the compressed-files cache on all devices. */
        private const val COMPRESS_RESET_V = 7  // bump → re-compress all images: full EXIF copy + correct paths
    }

    /**
     * Strips MediaStore auto-rename suffixes like " (1)", " (2)" from a filename so that
     * `photo (1).jpg` is treated as equivalent to `photo.jpg` in dedup checks.
     * e.g. "photo (1).jpg" → "photo.jpg", "IMG_20250101 (3).jpg" → "IMG_20250101.jpg"
     */
    /**
     * Resolves a phone filename to its USB counterpart using the same matching
     * rules the sync pass uses for confirmation, so anything confirmed as
     * "present on USB" can also be located for compression.
     *   1. exact match
     *   2. stripped (N) suffix match — e.g., "photo(1).jpg" → "photo.jpg"
     *   3. stem match — phone has "photo.jpg", USB has "photo" (no ext)
     *   4. USB file sharing the same stem with any extension
     */
    private fun resolveUsbName(phoneName: String, existing: Set<String>): String? {
        if (phoneName in existing) return phoneName
        val stripped = stripReplacementSuffixes(phoneName)
        if (stripped in existing) return stripped
        val stem = phoneName.substringBeforeLast('.')
        if (stem != phoneName && stem in existing) return stem
        // Last resort: find any USB file with the same stem (different extension)
        val strippedStem = stripped.substringBeforeLast('.')
        return existing.firstOrNull { it.substringBeforeLast('.') == strippedStem }
    }

    private fun stripReplacementSuffixes(name: String): String {
        val ext  = if ('.' in name) ".${name.substringAfterLast('.')}" else ""
        val stem = if (ext.isNotEmpty()) name.dropLast(ext.length) else name
        val stripped = stem.replace(Regex("""\s*\(\d+\)\s*$"""), "").trimEnd()
        return if (ext.isNotEmpty()) "$stripped$ext" else stripped
    }

    /** POSTs date corrections to /fix_dates on the client. Returns count of updated files. */
    private fun postFixDates(ip: String, corrections: List<DateCorrection>): Int {
        return try {
            val timestamp = System.currentTimeMillis()
            val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
            val signature = HmacAuth.sign(payload)

            val body = gson.toJson(corrections).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_FIX_DATES}")
                .post(body)
                .addHeader(Constants.HEADER_HMAC, signature)
                .addHeader(Constants.HEADER_TIMESTAMP, timestamp.toString())
                .addHeader(Constants.HEADER_DEVICE, myDeviceName)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.trim()?.toIntOrNull() ?: 0
                else 0
            }
        } catch (_: Exception) {
            0
        }
    }
}
