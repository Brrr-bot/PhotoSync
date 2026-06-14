package com.photosync.cloudsync.auth

import com.photosync.cloudsync.util.RemoteLogger
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OAuth2 **device-code flow** — designed for input-constrained devices like a tablet that's just
 * "on and connected." It shows a short user code + a URL; you enter the code in any browser once,
 * and we receive a long-lived refresh token. Works for both Google and Microsoft (Microsoft is a
 * public client, so [clientSecret] is null there).
 */
class DeviceCodeAuth(
    private val deviceCodeUrl: String,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String?,
    private val scope: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class DeviceCode(
        val deviceCode: String, val userCode: String, val verificationUrl: String,
        val intervalSec: Int, val expiresInSec: Int,
    )
    data class Token(val accessToken: String, val refreshToken: String?, val expiresInSec: Long)

    /** Step 1 — request a device + user code. */
    fun requestDeviceCode(): DeviceCode? {
        val form = FormBody.Builder().add("client_id", clientId).add("scope", scope).build()
        return try {
            http.newCall(Request.Builder().url(deviceCodeUrl).post(form).build()).execute().use { r ->
                val body = r.body?.string() ?: return null
                if (!r.isSuccessful) { RemoteLogger.e("device code HTTP ${r.code}: ${body.take(200)}"); return null }
                val j = JSONObject(body)
                DeviceCode(
                    deviceCode = j.getString("device_code"),
                    userCode = j.getString("user_code"),
                    verificationUrl = j.optString("verification_url").ifBlank { j.optString("verification_uri") },
                    intervalSec = j.optInt("interval", 5),
                    expiresInSec = j.optInt("expires_in", 900),
                )
            }
        } catch (t: Throwable) { RemoteLogger.e("requestDeviceCode failed", t); null }
    }

    /** Step 2 — poll until authorised or the code expires. Blocking; call off the main thread. */
    fun pollForToken(dc: DeviceCode): Token? {
        val deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L
        var interval = dc.intervalSec.coerceAtLeast(3)
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(interval * 1000L) } catch (_: InterruptedException) { return null }
            val fb = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", dc.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            if (!clientSecret.isNullOrBlank()) fb.add("client_secret", clientSecret)
            try {
                http.newCall(Request.Builder().url(tokenUrl).post(fb.build()).build()).execute().use { r ->
                    val body = r.body?.string() ?: return@use
                    val j = JSONObject(body)
                    if (r.isSuccessful && j.has("access_token")) {
                        return Token(
                            j.getString("access_token"),
                            j.optString("refresh_token").ifBlank { null },
                            j.optLong("expires_in", 3600),
                        )
                    }
                    when (j.optString("error")) {
                        "authorization_pending", "" -> {}      // keep waiting
                        "slow_down" -> interval += 5
                        else -> { RemoteLogger.e("device token error: ${j.optString("error")}"); return null }
                    }
                }
            } catch (t: Throwable) { RemoteLogger.e("pollForToken error", t) }
        }
        return null
    }

    /** Exchange a refresh token for a fresh access token. */
    fun refresh(refreshToken: String): Token? {
        val fb = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", scope)
        if (!clientSecret.isNullOrBlank()) fb.add("client_secret", clientSecret)
        return try {
            http.newCall(Request.Builder().url(tokenUrl).post(fb.build()).build()).execute().use { r ->
                val body = r.body?.string() ?: return null
                val j = JSONObject(body)
                if (r.isSuccessful && j.has("access_token"))
                    Token(j.getString("access_token"), j.optString("refresh_token").ifBlank { null }, j.optLong("expires_in", 3600))
                else { RemoteLogger.e("refresh HTTP ${r.code}: ${body.take(200)}"); null }
            }
        } catch (t: Throwable) { RemoteLogger.e("refresh failed", t); null }
    }
}
