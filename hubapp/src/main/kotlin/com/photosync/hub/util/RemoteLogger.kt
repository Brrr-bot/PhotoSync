package com.photosync.hub.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object RemoteLogger {
    private const val TAG = "RemoteLogger"
    private const val SERVER = "http://100.107.143.20:9000/log"
    private const val DEVICE = "hub"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun i(msg: String) = send("info", msg)
    fun e(msg: String, t: Throwable? = null) = send("error", if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg)

    private fun send(level: String, msg: String) {
        Log.d(TAG, "[$level] $msg")
        scope.launch {
            try {
                val json = """{"device":"$DEVICE","level":"$level","msg":${escapeJson(msg)}}"""
                val body = json.toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(SERVER).post(body).build()).execute().close()
            } catch (_: Throwable) {}
        }
    }

    private fun escapeJson(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
