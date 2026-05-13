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
