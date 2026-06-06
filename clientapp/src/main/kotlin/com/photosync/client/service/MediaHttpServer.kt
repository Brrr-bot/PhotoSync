package com.photosync.client.service

import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.media.ReplaceResult
import com.photosync.client.media.WebPConverter
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import com.photosync.shared.model.DateCorrection
import com.photosync.shared.model.HandshakeRequest
import com.photosync.shared.model.HandshakeResponse
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class MediaHttpServer(
    private val mediaStore: MediaStoreHelper,
    private val cacheDir: java.io.File,
    private val isOnMobileData: () -> Boolean = { false },
    private val onLog: ((String) -> Unit)? = null,
    private val onPendingDeletes: (() -> Unit)? = null,
    /** Called with the hub's Tailscale IP whenever a handshake carries one. */
    private val onHubTailscaleIp: ((String) -> Unit)? = null,
    /** Called when the laptop nudges the device to check for an OTA update immediately. */
    private val onNudge: (() -> Unit)? = null,
    /** Returns the set of filenames explicitly deleted by the user — excluded from /media/list. */
    private val deletedNames: () -> Set<String> = { emptySet() }
) : NanoHTTPD(Constants.CLIENT_PORT) {

    private val gson = Gson()
    private val myDeviceName = Build.MODEL

    // ── Polled state — MainActivity reads these every 200ms ───────────────────
    // Upload session (Phase 1)
    @Volatile var stateSessionCurrent: Int = 0
    @Volatile var stateSessionTotal: Int = 0
    @Volatile var stateCurrentFile: String = ""
    @Volatile var stateCurrentBytesRead: Long = 0L
    @Volatile var stateCurrentFileTotal: Long = 0L

    // Compression session (Phase 2)
    @Volatile var stateCompressionDone: Int = 0
    @Volatile var stateCompressionTotal: Int = 0
    @Volatile var stateCompressionFile: String = ""
    // Cumulative count of files compressed since the client started — survives across
    // batches so the UI can show "X compressed in this session" not just "1/1".
    @Volatile var stateCompressionLifetime: Int = 0

    // True while any sync activity is in progress
    @Volatile var stateActive: Boolean = false

    // True if any originals were queued for deletion (need user approval via createDeleteRequest)
    @Volatile var statePendingDeletes: Boolean = false

    private var sessionFileSizes = mapOf<Long, Long>()

    // ── CountingInputStream ───────────────────────────────────────────────────

    private inner class CountingInputStream(
        private val wrapped: InputStream,
        private val fileTotal: Long,
        private val intervalBytes: Long = 128 * 1024L
    ) : InputStream() {
        private var totalRead = 0L
        private var lastReported = -intervalBytes

        override fun read(): Int {
            val b = wrapped.read()
            if (b != -1) tick(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = wrapped.read(b, off, len)
            if (n > 0) tick(n.toLong())
            return n
        }

        override fun close() = wrapped.close()

        private fun tick(n: Long) {
            totalRead += n
            if (totalRead >= fileTotal || totalRead - lastReported >= intervalBytes) {
                lastReported = totalRead
                stateCurrentBytesRead = totalRead   // write directly to shared state
            }
        }
    }

    // ── Router ────────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        // Catch Throwable (not just Exception) so that OOM, ExifInterface internal errors,
        // and any other Error subclass never escape to the NanoHTTPD thread boundary.
        // An uncaught Error on a NanoHTTPD worker thread kills the whole process.
        return try {
            when {
                session.uri == Constants.PATH_HANDSHAKE -> {
                    onLog?.invoke("Handshake from ${session.remoteIpAddress}")
                    handleHandshake(session)
                }
                session.uri == Constants.PATH_MEDIA_LIST -> handleList(session)
                session.uri == Constants.PATH_MEDIA_FILE -> handleFile(session)
                session.uri == Constants.PATH_REPLACE    -> handleReplace(session)
                session.uri == Constants.PATH_FIX_DATES  -> handleFixDates(session)
                session.uri == "/nudge"      -> handleNudge()
                session.uri == "/log"        -> handleLogPost(session)
                session.uri.startsWith("/timesheet")     -> handleTimesheet(session)
                session.uri == "/" || session.uri == ""  -> handleHtmlStatus()
                session.uri == "/state.json" -> handleStateJson()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (t: Throwable) {
            onLog?.invoke("Unhandled error in serve() [${session.uri}]: ${t.javaClass.simpleName}: ${t.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "Server error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── /nudge ────────────────────────────────────────────────────────────────

    private fun handleNudge(): Response {
        onLog?.invoke("Update nudge received — checking for update now")
        onNudge?.invoke()
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    // ── /log  (POST — Timesheet app posts verified sessions) ─────────────────

    private fun handleLogPost(session: IHTTPSession): Response {
        return try {
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength.coerceAtMost(65536).toInt())
                var offset = 0
                while (offset < buf.size) {
                    val n = session.inputStream.read(buf, offset, buf.size - offset)
                    if (n < 0) break
                    offset += n
                }
                String(buf, 0, offset, Charsets.UTF_8)
            } else {
                ""
            }
            // Extract the "msg" field from JSON {"device":"...","level":"...","msg":"..."}
            val msgMatch = Regex(""""msg"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            val msg = msgMatch?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
                ?: body
            com.photosync.client.util.RemoteLogger.localLog?.let { f ->
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                f.appendText("$ts [client] INFO: $msg\n", Charsets.UTF_8)
            }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    // ── /timesheet  (GET ?month=YYYY-MM) ─────────────────────────────────────

    private fun handleTimesheet(session: IHTTPSession): Response {
        val month = session.parameters["month"]?.firstOrNull()
            ?: java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())

        val pattern = Regex(
            """\[TIMESHEET\]\s+(\d{4}-\d{2}-\d{2})\s+(.+?)\s+\((\w+)\)\s+""" +
            """(\d+)\s+period.*?×\s*(\d+)min\s*=\s*(\d+)min"""
        )

        val sessions  = mutableListOf<Map<String, Any>>()
        var totalMins = 0

        com.photosync.client.util.RemoteLogger.localLog?.takeIf { it.exists() }?.forEachLine { line ->
            if ("[TIMESHEET]" !in line) return@forEachLine
            val m = pattern.find(line) ?: return@forEachLine
            val (date, school, type, periods, mpp, tmins) = m.destructured
            if (!date.startsWith(month)) return@forEachLine
            val t = tmins.toInt()
            totalMins += t
            sessions.add(mapOf(
                "date" to date, "school" to school, "type" to type,
                "periods" to periods.toInt(), "mins_per_period" to mpp.toInt(),
                "total_mins" to t, "total_hours" to Math.round(t / 60.0 * 100) / 100.0
            ))
        }

        val json = gson.toJson(mapOf(
            "month"       to month,
            "total_hours" to Math.round(totalMins / 60.0 * 100) / 100.0,
            "total_mins"  to totalMins,
            "sessions"    to sessions
        ))
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    // ── / HTML status page ───────────────────────────────────────────────────

    private fun handleHtmlStatus(): Response {
        val html = buildHtmlPage()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleStateJson(): Response {
        val payload = mapOf(
            "active"         to stateActive,
            "uploadCurrent"  to stateSessionCurrent,
            "uploadTotal"    to stateSessionTotal,
            "uploadFile"     to stateCurrentFile,
            "compDone"       to stateCompressionDone,
            "compTotal"      to stateCompressionTotal,
            "compFile"       to stateCompressionFile,
            "compLifetime"   to stateCompressionLifetime,
            "device"         to myDeviceName,
            "time"           to java.text.SimpleDateFormat("HH:mm:ss",
                                java.util.Locale.getDefault()).format(java.util.Date())
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(payload))
    }

    private fun buildHtmlPage(): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val uploadCard = if (stateSessionTotal > 0) """
            <div class="card">
              <div class="lbl purple">UPLOAD</div>
              <div class="row">$stateSessionCurrent / $stateSessionTotal &nbsp;·&nbsp; ${esc(stateCurrentFile)}</div>
              <progress class="xfer" value="$stateSessionCurrent" max="$stateSessionTotal"></progress>
            </div>""" else ""

        val compressionCard = if (stateCompressionTotal > 0) """
            <div class="card">
              <div class="lbl green">COMPRESSION</div>
              <div class="row">$stateCompressionDone / $stateCompressionTotal &nbsp;·&nbsp; ${esc(stateCompressionFile)}</div>
              <progress class="comp" value="$stateCompressionDone" max="$stateCompressionTotal"></progress>
            </div>""" else ""

        val activeLabel = when {
            stateActive -> "<span class='ok'>● Active</span>"
            else        -> "<span style='color:#555'>○ Idle</span>"
        }

        // Static HTML scaffold — values are filled in client-side from /state.json polled
        // every 1.5 s. No meta-refresh, so the page never reloads or flashes.
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhotoSync Client</title>
<style>
*{box-sizing:border-box}
body{background:#0a0a0f;color:#c8c8d0;font-family:monospace;margin:0;padding:16px;font-size:13px}
h1{color:#00ff88;font-size:15px;letter-spacing:3px;margin:0 0 2px}
.ts{color:#555;font-size:10px;margin-bottom:18px}
.card{background:#12121a;border:1px solid #222;border-radius:8px;padding:16px;margin-bottom:14px}
.lbl{font-size:9px;letter-spacing:3px;font-weight:bold;margin-bottom:10px}
.green{color:#00ff88}.purple{color:#bf00ff}.amber{color:#ffaa00}
.mode{font-size:28px;font-weight:bold;margin-bottom:10px}
.row{margin-bottom:5px;line-height:1.5}
.ok{color:#00ff88}.warn{color:#ff4444}
progress{display:block;width:100%;height:6px;border:none;margin:6px 0 4px;-webkit-appearance:none;appearance:none}
progress::-webkit-progress-bar{background:#1a1a2e;border-radius:3px}
progress.xfer::-webkit-progress-value{background:#bf00ff;border-radius:3px}
progress.comp::-webkit-progress-value{background:#00ff88;border-radius:3px}
.hidden{display:none}
</style>
</head>
<body>
<h1>PHOTOSYNC CLIENT</h1>
<div class="ts">Live &nbsp;·&nbsp; <span id="time">${esc(time)}</span></div>
<div class="card">
  <div class="lbl green">STATUS</div>
  <div class="mode" id="mode">$activeLabel</div>
  <div class="row">Device: <span id="device">${esc(myDeviceName)}</span></div>
</div>
<div class="card hidden" id="uploadCard">
  <div class="lbl purple">UPLOAD</div>
  <div class="row"><span id="uploadCount"></span> &nbsp;·&nbsp; <span id="uploadFile"></span></div>
  <progress class="xfer" id="uploadBar" value="0" max="1"></progress>
</div>
<div class="card hidden" id="compCard">
  <div class="lbl green">COMPRESSION</div>
  <div class="row"><span id="compCount"></span> &nbsp;·&nbsp; <span id="compFile"></span></div>
  <progress class="comp" id="compBar" value="0" max="1"></progress>
</div>
<script>
function esc(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
async function tick(){
  try{
    const r = await fetch('/state.json',{cache:'no-store'});
    if(!r.ok) return;
    const s = await r.json();
    document.getElementById('time').textContent = s.time;
    document.getElementById('device').textContent = s.device;
    document.getElementById('mode').innerHTML = s.active
      ? "<span class='ok'>● Active</span>" : "<span style='color:#555'>○ Idle</span>";
    const uc = document.getElementById('uploadCard');
    if(s.uploadTotal > 0){
      uc.classList.remove('hidden');
      document.getElementById('uploadCount').textContent = s.uploadCurrent + ' / ' + s.uploadTotal;
      document.getElementById('uploadFile').textContent = s.uploadFile;
      document.getElementById('uploadBar').max = s.uploadTotal;
      document.getElementById('uploadBar').value = s.uploadCurrent;
    } else uc.classList.add('hidden');
    const cc = document.getElementById('compCard');
    if(s.compTotal > 0 || s.compLifetime > 0){
      cc.classList.remove('hidden');
      const lifetime = s.compLifetime || 0;
      const batch = (s.compTotal > 0) ? (s.compDone + ' / ' + s.compTotal) : 'idle';
      document.getElementById('compCount').textContent =
        lifetime + ' compressed total  ·  current batch ' + batch;
      document.getElementById('compFile').textContent = s.compFile || '';
      document.getElementById('compBar').max = Math.max(s.compTotal, 1);
      document.getElementById('compBar').value = s.compDone;
    } else cc.classList.add('hidden');
  }catch(e){}
}
tick(); setInterval(tick, 1500);
</script>
</body>
</html>"""
    }

    // ── /handshake ────────────────────────────────────────────────────────────

    private fun handleHandshake(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val body = HashMap<String, String>()
        session.parseBody(body)
        val json = body["postData"] ?: return badRequest("No body")
        val request = try {
            gson.fromJson(json, HandshakeRequest::class.java)
        } catch (e: Exception) { return badRequest("Invalid JSON") }

        if (!verifyHmac(session, request.timestamp, request.deviceName)) return unauthorized()

        // Store hub's Tailscale IP so the phone can reach the hub remotely
        request.hubTailscaleIp?.takeIf { it.isNotEmpty() }?.let { onHubTailscaleIp?.invoke(it) }

        val responseJson = gson.toJson(HandshakeResponse(accepted = true, deviceName = myDeviceName))
        return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
    }

    // ── /media/list ───────────────────────────────────────────────────────────

    private fun handleList(session: IHTTPSession): Response {
        if (!verifyHmacFromHeaders(session)) return unauthorized()

        val sinceSeconds = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
        val allFiles = mediaStore.getMediaSince(sinceSeconds)
        // Videos only sync over WiFi — filter them out when on mobile data
        val excluded = deletedNames()
        val files = run {
            val f = if (isOnMobileData()) allFiles.filter { it.mimeType.startsWith("image/") } else allFiles
            if (excluded.isEmpty()) f else f.filterNot { it.displayName in excluded }
        }

        if (sinceSeconds > 0L) {
            // Metadata scan — the hub fetches the full list to compare against USB, then
            // requests only the missing files via /media/file with dl_index/dl_total.
            // Do NOT set stateSessionTotal here (it's the whole library, not the upload set);
            // handleFile sets the real total from dl_total when actual uploads begin.
            stateCurrentFile = ""
            stateCurrentBytesRead = 0L
            stateCurrentFileTotal = 0L
            sessionFileSizes = files.associate { it.id to it.size }
            val imgCount = files.count { it.mimeType.startsWith("image/") }
            val vidCount = files.size - imgCount
            onLog?.invoke("Hub checking ${files.size} files ($imgCount photos, $vidCount videos)…")
        } else {
            // since=0 happens on every sync cycle (date-check pass). Do NOT reset the
            // compression counter here — it would flash back to 0/N on every cycle.
            // Instead let stateCompressionTotal/Done update naturally as /replace fires.
            // Only log when nothing else has been said recently.
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(files))
    }

    // ── /media/file ───────────────────────────────────────────────────────────

    private fun handleFile(session: IHTTPSession): Response {
        if (!verifyHmacFromHeaders(session)) return unauthorized()

        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return badRequest("Missing id")

        val (stream, mimeType) = mediaStore.openFileByIdAny(id)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        val fileSize = sessionFileSizes[id] ?: 0L
        val name = mediaStore.getDisplayName(id) ?: id.toString()

        // The hub tells us the real download set size via dl_index/dl_total, so the card
        // shows "K / N" of files actually being uploaded — not the full library count.
        val dlTotal = session.parameters["dl_total"]?.firstOrNull()?.toIntOrNull() ?: 0
        val dlIndex = session.parameters["dl_index"]?.firstOrNull()?.toIntOrNull() ?: 0
        if (dlTotal > 0) {
            stateSessionTotal = dlTotal
            stateSessionCurrent = dlIndex
        } else {
            stateSessionCurrent++
        }
        stateCurrentFile = name
        stateCurrentFileTotal = fileSize
        stateCurrentBytesRead = 0L
        stateActive = true

        val sizeStr = if (fileSize >= 1_048_576L) "%.1fMB".format(fileSize / 1_048_576.0)
                      else "${fileSize / 1024}KB"
        val counter = if (dlTotal > 0) " ($dlIndex/$dlTotal)" else ""
        onLog?.invoke("⬆ Uploading$counter $name · $sizeStr")

        val countingStream = CountingInputStream(stream, fileSize)
        return newChunkedResponse(Response.Status.OK, mimeType, countingStream)
    }

    // ── /replace ──────────────────────────────────────────────────────────────

    private fun handleReplace(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        if (!verifyHmacFromHeaders(session)) return unauthorized()

        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return badRequest("Missing id")
        val mime = (session.parameters["mime"]?.firstOrNull() ?: "image/jpeg")
            .replace("%2F", "/")
        val dateTaken = session.parameters[Constants.PARAM_DATE_TAKEN]?.firstOrNull()?.toLongOrNull() ?: 0L

        // Skip if this file was already replaced — avoids re-reading the full POST body
        if (mediaStore.isAlreadyReplaced(id)) {
            onLog?.invoke("Skipped replace for $id — already compressed")
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "REPLACED")
        }

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 0L)           return badRequest("Missing or zero Content-Length")
        if (contentLength > 100 * 1024 * 1024L) return badRequest("Body too large (max 100 MB)")

        // readNBytes(int) requires API 33+; use a manual loop so we work on Android 10-12.
        // Catch Throwable so OutOfMemoryError can't escape to the thread boundary.
        val bytes = try {
            val buf = ByteArray(contentLength.toInt())
            var offset = 0
            while (offset < buf.size) {
                val n = session.inputStream.read(buf, offset, buf.size - offset)
                if (n < 0) break
                offset += n
            }
            if (offset == 0) return badRequest("Empty body")
            if (offset < buf.size) buf.copyOf(offset) else buf
        } catch (t: Throwable) {
            return badRequest("Cannot read body: ${t.message}")
        }

        return try {
            val displayName = mediaStore.getDisplayName(id) ?: id.toString()
            stateCompressionFile = displayName
            // Hub-supplied batch progress: ?batch_index=K&batch_total=N tells us this is
            // file K of N in the current compression batch. If absent (older hub), we
            // fall back to the old "always equal" behaviour.
            val batchTotal = session.parameters["batch_total"]?.firstOrNull()?.toIntOrNull() ?: 0
            val batchIndex = session.parameters["batch_index"]?.firstOrNull()?.toIntOrNull() ?: 0
            if (batchTotal > 0) {
                stateCompressionTotal = batchTotal
                stateCompressionDone  = batchIndex.coerceAtMost(batchTotal)
            } else {
                if (stateCompressionDone >= stateCompressionTotal) stateCompressionTotal++
                stateCompressionDone++
            }
            // If the hub sent a JPEG, try to re-encode to WebP.
            // Only keep the WebP when it is strictly smaller — re-encoding an already-compressed
            // JPEG at high quality can produce a LARGER file, wasting space instead of saving it.
            val (finalBytes, finalMime) = if (mime.startsWith("image/") && mime != "image/webp") {
                val webp = WebPConverter.convert(bytes, cacheDir)
                if (webp != null && webp.size < bytes.size) Pair(webp, "image/webp") else Pair(bytes, mime)
            } else Pair(bytes, mime)

            val result = mediaStore.replaceFile(id, finalMime, finalBytes, dateTaken)
            stateCompressionLifetime++
            if (batchTotal == 0) { /* legacy: already incremented above */ }
            when (result) {
                ReplaceResult.REPLACED ->
                    onLog?.invoke("✓ Replaced $displayName (${stateCompressionDone}/${stateCompressionTotal})")
                ReplaceResult.COMPRESSED_PENDING_DELETE -> {
                    onLog?.invoke("Compressed $displayName — original queued for deletion (${stateCompressionDone}/${stateCompressionTotal})")
                    if (!statePendingDeletes) {
                        statePendingDeletes = true
                        onPendingDeletes?.invoke()   // notify service to post delete notification
                    }
                }
            }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, result.name)
        } catch (t: Throwable) {
            onLog?.invoke("Replace failed for $id: ${t.javaClass.simpleName}: ${t.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── /fix_dates ────────────────────────────────────────────────────────────

    private fun handleFixDates(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        if (!verifyHmacFromHeaders(session)) return unauthorized()

        val body = HashMap<String, String>()
        session.parseBody(body)
        val json = body["postData"] ?: return badRequest("No body")
        val corrections: List<DateCorrection> = try {
            val type = object : TypeToken<List<DateCorrection>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { return badRequest("Invalid JSON: ${e.message}") }

        val fixed = mediaStore.fixDates(corrections)
        if (fixed > 0) onLog?.invoke("Fixed dates for $fixed file(s)")
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, fixed.toString())
    }

    // ── HMAC ──────────────────────────────────────────────────────────────────

    private fun verifyHmacFromHeaders(session: IHTTPSession): Boolean {
        val hmac   = session.headers[Constants.HEADER_HMAC.lowercase()] ?: return false
        val tsStr  = session.headers[Constants.HEADER_TIMESTAMP.lowercase()] ?: return false
        val device = session.headers[Constants.HEADER_DEVICE.lowercase()] ?: return false
        val ts = tsStr.toLongOrNull() ?: return false
        return HmacAuth.verify(HmacAuth.buildPayload(ts, device), hmac, timestampMs = ts)
    }

    private fun verifyHmac(session: IHTTPSession, timestamp: Long, deviceName: String): Boolean {
        val hmac = session.headers[Constants.HEADER_HMAC.lowercase()] ?: return false
        return HmacAuth.verify(HmacAuth.buildPayload(timestamp, deviceName), hmac, timestampMs = timestamp)
    }

    private fun unauthorized()    = newFixedLengthResponse(Response.Status.UNAUTHORIZED,    MIME_PLAINTEXT, "Unauthorized")
    private fun badRequest(m: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, m)
    private fun methodNotAllowed() = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
}
