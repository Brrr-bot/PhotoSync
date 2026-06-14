package com.photosync.client.hub

import com.photosync.client.media.ImageIntegrity
import com.photosync.client.util.RemoteLogger
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HubFileEntry(
    val deviceName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

object HubFilesClient {

    // 30 s default — over Tailscale/DERP (or mobile data) the full file list can take several
    // seconds to arrive, well past the 10 s used for small requests, which would otherwise return
    // an empty list and make restores fail remotely ("not found on hub").
    fun fetchFiles(ip: String, port: Int, limit: Int = 50, timeoutMs: Int = 30_000): List<HubFileEntry> {
        return try {
            val conn = openGet("http://$ip:$port${Constants.PATH_HUB_FILES}?limit=$limit", timeoutMs = timeoutMs)
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                HubFileEntry(
                    deviceName    = obj.optString("deviceName"),
                    displayName   = obj.optString("displayName"),
                    sizeBytes     = obj.optLong("sizeBytes"),
                    lastModifiedMs = obj.optLong("lastModifiedMs")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun fetchThumbnail(ip: String, port: Int, device: String, name: String): ByteArray? {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openGet(
                "http://$ip:$port${Constants.PATH_HUB_THUMB}?device=$devEnc&name=$enc"
            )
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (!ImageIntegrity.isIntact(bytes, name)) {
                RemoteLogger.i("⚠ Download integrity FAIL: $name (${bytes.size}B) — discarded, will retry")
                return null
            }
            bytes
        } catch (_: Exception) { null }
    }

    /** Fetches ALL EXIF metadata (tag→value) of the hub original [name] for metadata restore. */
    fun fetchMeta(ip: String, port: Int, device: String, name: String): Map<String, String>? {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openGet("http://$ip:$port${Constants.PATH_HUB_META}?device=$devEnc&name=$enc")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val obj = JSONObject(body)
            val map = HashMap<String, String>()
            obj.keys().forEach { k -> map[k] = obj.getString(k) }
            map
        } catch (_: Exception) { null }
    }

    /** Asks the hub to generate a fresh full-size badged poster for video [name]. */
    fun fetchPoster(ip: String, port: Int, device: String, name: String): ByteArray? {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openGet(
                "http://$ip:$port${Constants.PATH_HUB_POSTER}?device=$devEnc&name=$enc",
                timeoutMs = 60_000
            )
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (!ImageIntegrity.isIntact(bytes, name)) {
                RemoteLogger.i("⚠ Download integrity FAIL: $name (${bytes.size}B) — discarded, will retry")
                return null
            }
            bytes
        } catch (_: Exception) { null }
    }

    fun fetchFile(ip: String, port: Int, device: String, name: String): ByteArray? {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openGet(
                "http://$ip:$port${Constants.PATH_HUB_FILE}?device=$devEnc&name=$enc",
                timeoutMs = 60_000
            )
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (!ImageIntegrity.isIntact(bytes, name)) {
                RemoteLogger.i("⚠ Download integrity FAIL: $name (${bytes.size}B) — discarded, will retry")
                return null
            }
            bytes
        } catch (_: Exception) { null }
    }

    /**
     * Streams [name] from the hub directly into [dest] without buffering the whole file in
     * memory — use for large files (videos) to avoid OutOfMemoryError. Returns true on success.
     */
    fun fetchFileToFile(
        ip: String, port: Int, device: String, name: String, dest: java.io.File,
        onProgress: ((bytesRead: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openGet(
                "http://$ip:$port${Constants.PATH_HUB_FILE}?device=$devEnc&name=$enc",
                timeoutMs = 120_000
            )
            if (conn.responseCode != 200) { conn.disconnect(); return false }
            val total = conn.contentLengthLong   // -1 if unknown
            var read  = 0L
            val buf   = ByteArray(65_536)
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        read += n
                        onProgress?.invoke(read, total)
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && dest.length() != total) {
                RemoteLogger.i("⚠ Download size mismatch: $name got ${dest.length()} of $total — discarded, will retry")
                dest.delete(); return false
            }
            dest.length() > 0
        } catch (_: Exception) { false }
    }

    /** Deletes [name] for [device] from the hub's USB drive. Returns true if the hub confirmed deletion. */
    fun deleteFile(ip: String, port: Int, device: String, name: String): Boolean {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openWithMethod("POST",
                "http://$ip:$port${Constants.PATH_HUB_DELETE}?device=$devEnc&name=$enc",
                timeoutMs = 30_000)
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    private fun openWithMethod(method: String, url: String, timeoutMs: Int = 10_000): HttpURLConnection {
        val ts = System.currentTimeMillis()
        val device = android.os.Build.MODEL
        val hmac = HmacAuth.sign(HmacAuth.buildPayload(ts, device))
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty(Constants.HEADER_HMAC, hmac)
        conn.setRequestProperty(Constants.HEADER_TIMESTAMP, ts.toString())
        conn.setRequestProperty(Constants.HEADER_DEVICE, device)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn
    }

    private fun openGet(url: String, timeoutMs: Int = 10_000): HttpURLConnection {
        val ts = System.currentTimeMillis()
        val device = android.os.Build.MODEL
        val hmac = HmacAuth.sign(HmacAuth.buildPayload(ts, device))
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty(Constants.HEADER_HMAC, hmac)
        conn.setRequestProperty(Constants.HEADER_TIMESTAMP, ts.toString())
        conn.setRequestProperty(Constants.HEADER_DEVICE, device)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn
    }
}
