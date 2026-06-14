package com.photosync.cloudsync.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight logger: logcat + an in-app sink (for the UI) + a rolling local file.
 * No network — CloudSync runs locally on the tablet.
 */
object RemoteLogger {
    private const val TAG = "CloudSync"
    private val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var localLog: File? = null
    @Volatile var onMessage: ((String) -> Unit)? = null

    fun i(msg: String) = emit("INFO", msg)
    fun e(msg: String, t: Throwable? = null) =
        emit("ERR", if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg)

    private fun emit(level: String, msg: String) {
        Log.d(TAG, "[$level] $msg")
        val line = "${tsFmt.format(Date())} $msg"
        try { onMessage?.invoke(line) } catch (_: Throwable) {}
        localLog?.let { f ->
            try {
                f.parentFile?.mkdirs()
                // Keep the file from growing without bound.
                if (f.exists() && f.length() > 512 * 1024) f.writeText("")
                f.appendText("$line\n", Charsets.UTF_8)
            } catch (_: Throwable) {}
        }
    }
}
