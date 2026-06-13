package com.photosync.client.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object RemoteLogger {
    private const val TAG    = "RemoteLogger"
    private const val SERVER = "https://app-updates.mcubittbuilders.workers.dev/api/log"
    private const val DEVICE = "client"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Local log file — set once from ClientApplication.onCreate(). */
    @Volatile var localLog: File? = null

    /** Optional sink so EVERY log line (incl. ones from background managers) reaches the in-app
     *  live card. Set once from the service. Without this, only service log() calls were broadcast. */
    @Volatile var onMessage: ((String) -> Unit)? = null

    fun i(msg: String) = send("info",  msg)
    fun e(msg: String, t: Throwable? = null) =
        send("error", if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg)

    private fun send(level: String, msg: String) {
        Log.d(TAG, "[$level] $msg")

        // Write to local log file immediately (no coroutine — keep ordering)
        localLog?.let { f ->
            try {
                val ts   = tsFmt.format(Date())
                val line = "$ts [client] ${level.uppercase()}: $msg\n"
                f.parentFile?.mkdirs()
                f.appendText(line, Charsets.UTF_8)
            } catch (_: Throwable) {}
        }

        // Fan out to the in-app live card (and any other UI sink) synchronously, in order.
        try { onMessage?.invoke(msg) } catch (_: Throwable) {}

        // Best-effort send to remote server
        scope.launch {
            try {
                val json = """{"app":"$DEVICE","level":"$level","msg":${escapeJson(msg)}}"""
                val body = json.toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(SERVER).post(body).build()).execute().close()
            } catch (_: Throwable) {}
        }
    }

    private fun escapeJson(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
