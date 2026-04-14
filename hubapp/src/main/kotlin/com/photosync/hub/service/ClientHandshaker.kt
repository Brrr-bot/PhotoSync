package com.photosync.hub.service

import android.os.Build
import com.google.gson.Gson
import com.photosync.hub.network.TailscaleIpDetector
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import com.photosync.shared.model.HandshakeRequest
import com.photosync.shared.model.HandshakeResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ClientInfo(val ip: String, val mac: String, val deviceName: String)

/** Result of a handshake attempt — either success or a reason string for logging. */
sealed class HandshakeResult {
    data class Success(val info: ClientInfo) : HandshakeResult()
    data class Failure(val reason: String) : HandshakeResult()
}

class ClientHandshaker {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val myDeviceName: String = Build.MODEL

    fun tryHandshake(ip: String, mac: String): HandshakeResult {
        return try {
            val timestamp = System.currentTimeMillis()
            val payload = HmacAuth.buildPayload(timestamp, myDeviceName)
            val signature = HmacAuth.sign(payload)

            val tailscaleIp = TailscaleIpDetector.getIp()
            val body = gson.toJson(HandshakeRequest(myDeviceName, timestamp, tailscaleIp))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("http://$ip:${Constants.CLIENT_PORT}${Constants.PATH_HANDSHAKE}")
                .post(body)
                .addHeader(Constants.HEADER_HMAC, signature)
                .addHeader(Constants.HEADER_TIMESTAMP, timestamp.toString())
                .addHeader(Constants.HEADER_DEVICE, myDeviceName)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    return HandshakeResult.Failure("HTTP ${response.code}")
                val responseBody = response.body?.string()
                    ?: return HandshakeResult.Failure("empty response body")
                val handshake = runCatching {
                    gson.fromJson(responseBody, HandshakeResponse::class.java)
                }.getOrElse {
                    return HandshakeResult.Failure("bad JSON: $responseBody")
                }
                if (!handshake.accepted)
                    return HandshakeResult.Failure("server rejected (accepted=false)")
                HandshakeResult.Success(ClientInfo(ip, mac, handshake.deviceName))
            }
        } catch (e: Exception) {
            HandshakeResult.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
