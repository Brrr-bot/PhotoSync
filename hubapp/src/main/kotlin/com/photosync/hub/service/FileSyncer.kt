package com.photosync.hub.service

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photosync.hub.compress.MediaCompressor
import com.photosync.hub.storage.SyncStateRepository
import com.photosync.hub.storage.UsbStorageManager
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

    fun syncDevice(clientInfo: ClientInfo): Int {

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

        val lastSync   = syncState.getLastSync(clientInfo.mac)
        val lastSyncSeconds = lastSync / 1000

        val allFiles      = fetchFileList(clientInfo.ip, lastSyncSeconds) ?: return 0
        val existing      = usbStorage.getExistingFileNames(clientInfo.deviceName)
        val downloadedIds = syncState.getDownloadedIds(clientInfo.deviceName)
        val alreadyCompressed = syncState.getCompressedFiles(clientInfo.deviceName).toMutableSet()

        // Triple dedup guard (see UsbStorageManager for SAF extension-mangling details)
        val toProcess = allFiles.filter { file ->
            file.id !in downloadedIds &&
            file.displayName !in existing &&
            file.displayName.substringBeforeLast('.') !in existing
        }

        var synced = 0
        var latestTimestamp = lastSync
        val totalSessionBytes = toProcess.sumOf { it.size }
        var sessionBytesCompleted = 0L

        if (toProcess.isEmpty()) {
            val newestTs = allFiles.maxOfOrNull { it.dateAdded * 1000 } ?: lastSync
            if (newestTs > lastSync) syncState.updateLastSync(clientInfo.mac, newestTs)
            onProgress("${clientInfo.deviceName}: up to date (${allFiles.size} files on phone)")
        } else {
            onProgress("${clientInfo.deviceName}: ${toProcess.size} new file(s) to sync…")

            for ((index, file) in toProcess.withIndex()) {
                try {
                    val remaining = totalSessionBytes - sessionBytesCompleted
                    onTransferProgress?.invoke(
                        index + 1, toProcess.size, file.displayName, file.size, remaining
                    )

                    if (MediaCompressor.canCompress(file.mimeType)) {
                        // ── Image: download → save to USB → compress → replace ──────────
                        val bytes = downloadBytes(clientInfo.ip, file.id)
                        usbStorage.writeFile(clientInfo.deviceName, file, ByteArrayInputStream(bytes))
                        onFileBytes?.invoke(bytes.size.toLong(), bytes.size.toLong(), file.displayName)

                        if (file.displayName !in alreadyCompressed) {
                            val dateTakenMs = if (file.dateTaken > 0) file.dateTaken
                                             else if (file.dateAdded > 0) file.dateAdded * 1000L
                                             else 0L
                            val compressed = MediaCompressor.compressImage(bytes, dateTakenMs, context.cacheDir)
                            if (compressed != null) {
                                // Signal compression phase to hub UI (fileSizeBytes = 0 is the flag)
                                onTransferProgress?.invoke(
                                    index + 1, toProcess.size, file.displayName, 0L, 0L
                                )
                                val ok = postReplace(clientInfo.ip, file.id, "image/jpeg", compressed, dateTakenMs)
                                if (ok) {
                                    syncState.markCompressed(clientInfo.deviceName, file.displayName)
                                    alreadyCompressed += file.displayName
                                    onProgress(
                                        "✓ ${file.displayName}  " +
                                        "${bytes.size / 1024}KB → ${compressed.size / 1024}KB"
                                    )
                                } else {
                                    onProgress("✓ ${file.displayName} saved (replace failed — will retry)")
                                }
                            } else {
                                // Already at optimal size — no point replacing
                                syncState.markCompressed(clientInfo.deviceName, file.displayName)
                                alreadyCompressed += file.displayName
                                onProgress("✓ ${file.displayName} saved (already optimal)")
                            }
                        } else {
                            onProgress("✓ ${file.displayName} saved")
                        }

                    } else {
                        // ── Video / other: stream directly to USB, no compression ────────
                        downloadStream(clientInfo.ip, file.id, clientInfo.deviceName, file)
                        onProgress("✓ ${file.displayName} saved")
                    }

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

            if (synced > 0) syncState.updateLastSync(clientInfo.mac, latestTimestamp)
            onProgress("${clientInfo.deviceName}: $synced/${toProcess.size} files synced")
        }

        // ── Legacy cleanup: compress files downloaded before this app version ─────
        // These exist on USB but never had a replace step (old sessions or failed replaces).
        // We only pay the fetchFileList(since=0) cost when there's actually work to do.
        val onUsbNow = usbStorage.getExistingFileNames(clientInfo.deviceName)
        val legacyCompressed = syncState.getCompressedFiles(clientInfo.deviceName)
        // Quick check: any image on USB that isn't marked compressed?
        val needsLegacyPass = onUsbNow.any { name ->
            name !in legacyCompressed &&
            (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
             name.endsWith(".png", true) || name.endsWith(".webp", true) ||
             name.endsWith(".heic", true) || name.endsWith(".heif", true))
        }

        if (needsLegacyPass) {
            val allFilesEver = fetchFileList(clientInfo.ip, 0L) ?: return synced
            val toCompress = allFilesEver.filter { file ->
                MediaCompressor.canCompress(file.mimeType) &&
                file.displayName !in legacyCompressed &&
                file.displayName in onUsbNow
            }

            if (toCompress.isNotEmpty()) {
                onProgress("${clientInfo.deviceName}: ${toCompress.size} older image(s) to compress…")
                var compressed = 0
                for ((index, file) in toCompress.withIndex()) {
                    try {
                        onTransferProgress?.invoke(
                            index + 1, toCompress.size, file.displayName, 0L, 0L
                        )
                        val bytes = usbStorage.readFile(clientInfo.deviceName, file.displayName)
                        if (bytes == null) {
                            syncState.markCompressed(clientInfo.deviceName, file.displayName)
                            continue
                        }
                        val legacyDateMs = if (file.dateTaken > 0) file.dateTaken
                                          else if (file.dateAdded > 0) file.dateAdded * 1000L
                                          else 0L
                        val compressedBytes = MediaCompressor.compressImage(bytes, legacyDateMs, context.cacheDir)
                        if (compressedBytes == null) {
                            syncState.markCompressed(clientInfo.deviceName, file.displayName)
                            continue
                        }
                        val ok = postReplace(clientInfo.ip, file.id, "image/jpeg", compressedBytes, legacyDateMs)
                        if (ok) {
                            compressed++
                            syncState.markCompressed(clientInfo.deviceName, file.displayName)
                            onProgress(
                                "✓ ${file.displayName} compressed: " +
                                "${bytes.size / 1024}KB → ${compressedBytes.size / 1024}KB"
                            )
                        } else {
                            onProgress("✗ ${file.displayName}: replace failed")
                        }
                    } catch (e: java.net.ConnectException) {
                        onProgress("✗ Client unreachable — stopping compression")
                        break
                    } catch (e: java.net.SocketTimeoutException) {
                        onProgress("✗ Client timed out — stopping compression")
                        break
                    } catch (e: Exception) {
                        onProgress("✗ ${file.displayName}: ${e.message}")
                    }
                }
                onProgress("${clientInfo.deviceName}: $compressed/${toCompress.size} older images compressed")
            }
        }

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

    /** Streams a file directly to USB — never loads it fully into memory. Use for videos. */
    private fun downloadStream(ip: String, id: Long, deviceName: String, file: MediaFileInfo) {
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

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.bytes() ?: throw Exception("empty body")
        }
    }

    /** POSTs compressed bytes to /replace on the client. Returns true on success. */
    private fun postReplace(ip: String, id: Long, mime: String, bytes: ByteArray, dateTaken: Long = 0L): Boolean {
        return try {
            val timestamp = System.currentTimeMillis()
            val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
            val signature = HmacAuth.sign(payload)

            val urlBuilder = StringBuilder(
                "http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_REPLACE}?id=$id&mime=${mime.replace("/", "%2F")}"
            )
            if (dateTaken > 0L) urlBuilder.append("&${Constants.PARAM_DATE_TAKEN}=$dateTaken")

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
