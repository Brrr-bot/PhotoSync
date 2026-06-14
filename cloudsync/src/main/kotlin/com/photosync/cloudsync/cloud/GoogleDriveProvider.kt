package com.photosync.cloudsync.cloud

import com.photosync.cloudsync.CloudConfig
import com.photosync.cloudsync.auth.DeviceCodeAuth
import com.photosync.cloudsync.auth.TokenStore
import com.photosync.cloudsync.util.RemoteLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Google Drive via Drive API v3 + OAuth2 device flow (scope: drive.readonly). */
class GoogleDriveProvider(private val tokens: TokenStore) : CloudProvider {
    override val key = "google"
    override val label = "Google Drive"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val auth = DeviceCodeAuth(
        CloudConfig.GOOGLE_DEVICE_CODE_URL, CloudConfig.GOOGLE_TOKEN_URL,
        CloudConfig.GOOGLE_CLIENT_ID, CloudConfig.GOOGLE_CLIENT_SECRET, CloudConfig.GOOGLE_SCOPE)

    override fun isConfigured() = CloudConfig.googleConfigured()
    override fun isAuthed() = tokens.isAuthed(key)
    override fun beginAuth() = auth.requestDeviceCode()
    override fun awaitAuth(dc: DeviceCodeAuth.DeviceCode): Boolean {
        val t = auth.pollForToken(dc) ?: return false
        tokens.save(key, t.accessToken, t.refreshToken, t.expiresInSec); return true
    }

    private fun accessToken(): String? {
        tokens.validAccessToken(key)?.let { return it }
        val rt = tokens.refreshToken(key) ?: return null
        val t = auth.refresh(rt) ?: return null
        tokens.save(key, t.accessToken, t.refreshToken, t.expiresInSec)
        return t.accessToken
    }

    override fun listAllMedia(onBatch: (List<CloudFile>) -> Unit): Boolean {
        val token = accessToken() ?: run { RemoteLogger.e("Google: no token"); return false }
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val exifFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        var pageToken: String? = null
        do {
            val q = URLEncoder.encode("(mimeType contains 'image/' or mimeType contains 'video/') and trashed = false", "UTF-8")
            val fields = URLEncoder.encode("nextPageToken,files(id,name,mimeType,size,createdTime,imageMediaMetadata/time)", "UTF-8")
            val url = StringBuilder("${CloudConfig.DRIVE_FILES_URL}?q=$q&fields=$fields&pageSize=1000&spaces=drive")
            if (pageToken != null) url.append("&pageToken=").append(pageToken)
            val req = Request.Builder().url(url.toString()).header("Authorization", "Bearer $token").get().build()
            try {
                http.newCall(req).execute().use { r ->
                    val body = r.body?.string() ?: return false
                    if (!r.isSuccessful) { RemoteLogger.e("Google list HTTP ${r.code}: ${body.take(200)}"); return false }
                    val j = JSONObject(body)
                    val arr = j.optJSONArray("files")
                    val out = ArrayList<CloudFile>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val mime = f.optString("mimeType", "")
                        var dateMs = 0L
                        val exifTime = f.optJSONObject("imageMediaMetadata")?.optString("time")
                        if (!exifTime.isNullOrBlank()) dateMs = runCatching { exifFmt.parse(exifTime)?.time ?: 0L }.getOrDefault(0L)
                        if (dateMs == 0L) {
                            val ct = f.optString("createdTime")
                            if (ct.length >= 19) dateMs = runCatching { isoFmt.parse(ct.substring(0, 19))?.time ?: 0L }.getOrDefault(0L)
                        }
                        out.add(CloudFile(key, f.getString("id"), f.optString("name", f.getString("id")), mime,
                            f.optString("size", "0").toLongOrNull() ?: 0L, dateMs, null))
                    }
                    if (out.isNotEmpty()) onBatch(out)
                    pageToken = j.optString("nextPageToken").ifBlank { null }
                }
            } catch (t: Throwable) { RemoteLogger.e("Google list error", t); return false }
        } while (pageToken != null)
        return true
    }

    override fun download(file: CloudFile): ByteArray? {
        val token = accessToken() ?: return null
        val url = "${CloudConfig.DRIVE_FILES_URL}/${file.id}?alt=media"
        return try {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()).execute().use { r ->
                if (!r.isSuccessful) { RemoteLogger.e("Google download HTTP ${r.code} ${file.name}"); return null }
                r.body?.bytes()
            }
        } catch (t: Throwable) { RemoteLogger.e("Google download error ${file.name}", t); null }
    }
}
