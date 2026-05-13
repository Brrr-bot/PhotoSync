package com.photosync.hub.service

import com.google.gson.Gson
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import com.photosync.shared.model.DashboardStatusResponse
import fi.iki.elonen.NanoHTTPD

class HubHttpServer(
    private val onSyncRequest: (clientIp: String) -> Unit,
    private val onDashboardRequest: () -> DashboardStatusResponse,
    private val onNudge: (() -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null,
    private val onLocation: ((json: String) -> Unit)? = null,
    private val onFilesRequest: ((limit: Int) -> List<com.photosync.hub.storage.UsbStorageManager.HubFileEntry>)? = null,
    private val onThumbRequest: ((device: String, name: String) -> ByteArray?)? = null,
    private val onFileRequest: ((device: String, name: String) -> ByteArray?)? = null
) : NanoHTTPD(Constants.HUB_HTTP_PORT) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                Constants.PATH_SYNC -> handleSync(session)
                Constants.PATH_LOCATION -> handleLocation(session)
                Constants.PATH_DASHBOARD -> handleDashboard(session)
                Constants.PATH_HUB_FILES -> handleHubFiles(session)
                Constants.PATH_HUB_THUMB -> handleHubThumb(session)
                Constants.PATH_HUB_FILE  -> handleHubFile(session)
                "/push" -> handlePush(session)
                "/nudge" -> handleNudge()
                "/", "" -> handleHtmlDashboard()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (t: Throwable) {
            onLog?.invoke("HubHttpServer error [${session.uri}]: ${t.javaClass.simpleName}: ${t.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error")
        }
    }

    private fun handleSync(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST required")
        }
        if (!verifyHmacFromHeaders(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }

        val ip = session.remoteIpAddress ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Cannot determine client IP"
        )

        onLog?.invoke("Sync requested by $ip")
        onSyncRequest(ip)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleLocation(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST required")
        }
        if (!verifyHmacFromHeaders(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        val body = try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (_: Exception) { "" }
        if (body.isNotEmpty()) onLocation?.invoke(body)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleDashboard(session: IHTTPSession): Response {
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET required")
        }
        if (!verifyHmacFromHeaders(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        val json = gson.toJson(onDashboardRequest())
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleHtmlDashboard(): Response {
        val s = onDashboardRequest()
        val html = buildHtmlPage(s)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    /**
     * Unauthenticated push trigger — safe because it's only reachable over Tailscale/local network.
     * Triggers a sync for the last known connected client IP (from the "X-Forwarded-For" or remote IP).
     * The hub's activeSyncs guard prevents double-syncing if one is already in progress.
     */
    private fun handlePush(session: IHTTPSession): Response {
        val ip = session.headers["x-forwarded-for"]?.split(",")?.firstOrNull()?.trim()
            ?: session.remoteIpAddress
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Cannot determine IP")
        onLog?.invoke("Manual push triggered from $ip")
        onSyncRequest(ip)
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
            "<html><head><meta http-equiv='refresh' content='2;url=/'></head>" +
            "<body style='background:#0a0a0f;color:#00ff88;font-family:monospace;padding:20px'>" +
            "Sync triggered — redirecting…</body></html>")
        return resp
    }

    private fun handleHubFiles(session: IHTTPSession): Response {
        if (session.method != Method.GET)
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET required")
        if (!verifyHmacFromHeaders(session))
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val files = onFilesRequest?.invoke(limit) ?: emptyList()
        val json = gson.toJson(files)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleHubThumb(session: IHTTPSession): Response {
        if (session.method != Method.GET)
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET required")
        if (!verifyHmacFromHeaders(session))
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        val device = session.parameters["device"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing device")
        val name = session.parameters["name"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing name")
        val bytes = onThumbRequest?.invoke(device, name)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun handleHubFile(session: IHTTPSession): Response {
        if (session.method != Method.GET)
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "GET required")
        if (!verifyHmacFromHeaders(session))
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        val device = session.parameters["device"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing device")
        val name = session.parameters["name"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing name")
        val bytes = onFileRequest?.invoke(device, name)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        val mime = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"  -> "image/png"
            "webp" -> "image/webp"
            "mp4"  -> "video/mp4"
            "mov"  -> "video/quicktime"
            else   -> "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime,
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun buildHtmlPage(s: DashboardStatusResponse): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(s.updatedAt))

        fun bool(b: Boolean) = if (b) "<span class='ok'>✓</span>" else "<span class='warn'>✗</span>"
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val progressCard = if (s.progressTotal > 0) """
            <div class="card">
              <div class="lbl purple">TRANSFER</div>
              <div class="row">${s.progressCurrent} / ${s.progressTotal} &nbsp;·&nbsp; ${esc(s.currentFile)}</div>
              <progress class="xfer" value="${s.progressCurrent}" max="${s.progressTotal}"></progress>
            </div>""" else ""

        val compressionCard = if (s.compressionTotal > 0) """
            <div class="card">
              <div class="lbl green">COMPRESSION</div>
              <div class="row">${s.compressionCurrent} / ${s.compressionTotal}</div>
              <progress class="comp" value="${s.compressionCurrent}" max="${s.compressionTotal}"></progress>
            </div>""" else ""

        val logs = s.recentLogs.takeLast(40).joinToString("\n") { esc(it) }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhotoSync Hub</title>
<style>
*{box-sizing:border-box}
body{background:#0a0a0f;color:#c8c8d0;font-family:monospace;margin:0;padding:16px;font-size:13px}
h1{color:#00e5ff;font-size:15px;letter-spacing:3px;margin:0 0 2px}
.ts{color:#555;font-size:10px;margin-bottom:18px}
.card{background:#12121a;border:1px solid #222;border-radius:8px;padding:16px;margin-bottom:14px}
.lbl{font-size:9px;letter-spacing:3px;font-weight:bold;margin-bottom:10px}
.cyan{color:#00e5ff}.green{color:#00ff88}.purple{color:#bf00ff}.amber{color:#ffaa00}
.mode{font-size:28px;font-weight:bold;color:#00ff88;margin-bottom:10px}
.row{margin-bottom:5px;line-height:1.5}
.ok{color:#00ff88}.warn{color:#ff4444}
.log{font-size:10px;line-height:1.7;white-space:pre-wrap;word-break:break-all;color:#888}
progress{display:block;width:100%;height:6px;border:none;margin:6px 0 4px;-webkit-appearance:none;appearance:none}
progress::-webkit-progress-bar{background:#1a1a2e;border-radius:3px}
progress.xfer::-webkit-progress-value{background:#bf00ff;border-radius:3px}
progress.comp::-webkit-progress-value{background:#00ff88;border-radius:3px}
</style>
</head>
<body>
<h1>PHOTOSYNC HUB</h1>
<div class="ts">Auto-refreshes every 8 s &nbsp;·&nbsp; Updated: ${esc(time)}</div>
<div class="card">
  <div class="lbl cyan">STATUS</div>
  <div class="mode">${esc(s.currentMode)}</div>
  <div class="row">${bool(s.hubReady)} USB drive ready</div>
  <div class="row">${bool(s.batteryOptimizedIgnored)} Battery optimization exempt</div>
  <div class="row">${bool(s.accessibilityEnabled)} Keep-alive service active</div>
  <div class="row" style="color:#555;margin-top:10px;font-size:11px">${esc(s.lastSyncSummary)}</div>
</div>
${progressCard}${compressionCard}<div class="card">
  <div class="lbl amber">LIVE LOG</div>
  <div class="log">${logs}</div>
</div>
</body>
</html>"""
    }

    /** Unauthenticated — laptop pings this immediately after pushing a new APK. */
    private fun handleNudge(): Response {
        onLog?.invoke("Update nudge received — checking for update now")
        onNudge?.invoke()
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun verifyHmacFromHeaders(session: IHTTPSession): Boolean {
        val hmac = session.headers[Constants.HEADER_HMAC.lowercase()] ?: return false
        val tsStr = session.headers[Constants.HEADER_TIMESTAMP.lowercase()] ?: return false
        val device = session.headers[Constants.HEADER_DEVICE.lowercase()] ?: return false
        val ts = tsStr.toLongOrNull() ?: return false
        return HmacAuth.verify(HmacAuth.buildPayload(ts, device), hmac, timestampMs = ts)
    }
}
