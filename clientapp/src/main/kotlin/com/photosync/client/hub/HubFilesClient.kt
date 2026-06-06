package com.photosync.client.hub

import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class HubFileEntry(
    val deviceName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

object HubFilesClient {

    fun fetchFiles(ip: String, port: Int, limit: Int = 50): List<HubFileEntry> {
        return try {
            val conn = openGet("http://$ip:$port${Constants.PATH_HUB_FILES}?limit=$limit")
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
            bytes
        } catch (_: Exception) { null }
    }

    /**
     * Streams [name] from the hub directly into [dest] without buffering the whole file in
     * memory — use for large files (videos) to avoid OutOfMemoryError. Returns true on success.
     */
    fun fetchFileToFile(ip: String, port: Int, device: String, name: String, dest: java.io.File): Boolean {
        return try {
            val enc = java.net.URLEncoder.encode(name, "UTF-8")
            val devEnc = java.net.URLEncoder.encode(device, "UTF-8")
            val conn = openGet(
                "http://$ip:$port${Constants.PATH_HUB_FILE}?device=$devEnc&name=$enc",
                timeoutMs = 120_000
            )
            if (conn.responseCode != 200) { conn.disconnect(); return false }
            conn.inputStream.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            conn.disconnect()
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
