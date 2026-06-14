package com.photosync.cloudsync.cloud

import com.photosync.cloudsync.CloudConfig
import com.photosync.cloudsync.auth.DeviceCodeAuth
import com.photosync.cloudsync.auth.TokenStore
import com.photosync.cloudsync.util.RemoteLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** OneDrive via Microsoft Graph (delta enumeration) + OAuth2 device flow (scope: Files.Read). */
class OneDriveProvider(private val tokens: TokenStore) : CloudProvider {
    override val key = "onedrive"
    override val label = "OneDrive"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val auth = DeviceCodeAuth(
        CloudConfig.MS_DEVICE_CODE_URL, CloudConfig.MS_TOKEN_URL,
        CloudConfig.MS_CLIENT_ID, null, CloudConfig.MS_SCOPE)

    override fun isConfigured() = CloudConfig.msConfigured()
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
        val token = accessToken() ?: run { RemoteLogger.e("OneDrive: no token"); return false }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var url: String? = CloudConfig.GRAPH_DELTA_URL +
            "?\$select=id,name,file,photo,size,createdDateTime,@microsoft.graph.downloadUrl"
        do {
            val req = Request.Builder().url(url!!).header("Authorization", "Bearer $token").get().build()
            try {
                http.newCall(req).execute().use { r ->
                    val body = r.body?.string() ?: return false
                    if (!r.isSuccessful) { RemoteLogger.e("OneDrive list HTTP ${r.code}: ${body.take(200)}"); return false }
                    val j = JSONObject(body)
                    val arr = j.optJSONArray("value")
                    val out = ArrayList<CloudFile>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val fileObj = item.optJSONObject("file") ?: continue   // skip folders
                        val mime = fileObj.optString("mimeType", "")
                        if (!(mime.startsWith("image/") || mime.startsWith("video/"))) continue
                        var dateMs = 0L
                        val taken = item.optJSONObject("photo")?.optString("takenDateTime")
                        if (!taken.isNullOrBlank() && taken.length >= 19)
                            dateMs = runCatching { iso.parse(taken.substring(0, 19))?.time ?: 0L }.getOrDefault(0L)
                        if (dateMs == 0L) {
                            val ct = item.optString("createdDateTime")
                            if (ct.length >= 19) dateMs = runCatching { iso.parse(ct.substring(0, 19))?.time ?: 0L }.getOrDefault(0L)
                        }
                        out.add(CloudFile(key, item.getString("id"), item.optString("name", item.getString("id")),
                            mime, item.optLong("size", 0L), dateMs,
                            item.optString("@microsoft.graph.downloadUrl").ifBlank { null }))
                    }
                    if (out.isNotEmpty()) onBatch(out)
                    // Delta paginates with @odata.nextLink and ends with @odata.deltaLink (no nextLink).
                    url = j.optString("@odata.nextLink").ifBlank { null }
                }
            } catch (t: Throwable) { RemoteLogger.e("OneDrive list error", t); return false }
        } while (url != null)
        return true
    }

    override fun download(file: CloudFile): ByteArray? {
        // Prefer the pre-authenticated downloadUrl; fall back to /content with a bearer token.
        val direct = file.downloadUrl
        if (!direct.isNullOrBlank()) {
            try {
                http.newCall(Request.Builder().url(direct).get().build()).execute().use { r ->
                    if (r.isSuccessful) return r.body?.bytes()
                }
            } catch (t: Throwable) { RemoteLogger.e("OneDrive direct download error ${file.name}", t) }
        }
        val token = accessToken() ?: return null
        val url = "https://graph.microsoft.com/v1.0/me/drive/items/${file.id}/content"
        return try {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()).execute().use { r ->
                if (!r.isSuccessful) { RemoteLogger.e("OneDrive download HTTP ${r.code} ${file.name}"); return null }
                r.body?.bytes()
            }
        } catch (t: Throwable) { RemoteLogger.e("OneDrive download error ${file.name}", t); null }
    }
}
