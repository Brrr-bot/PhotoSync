package com.photosync.client.service

import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.media.ReplaceResult
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import com.photosync.shared.model.DateCorrection
import com.photosync.shared.model.HandshakeRequest
import com.photosync.shared.model.HandshakeResponse
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class MediaHttpServer(
    private val mediaStore: MediaStoreHelper,
    private val onLog: ((String) -> Unit)? = null,
    private val onPendingDeletes: (() -> Unit)? = null,
    /** Called with the hub's Tailscale IP whenever a handshake carries one. */
    private val onHubTailscaleIp: ((String) -> Unit)? = null
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
            when (session.uri) {
                Constants.PATH_HANDSHAKE -> {
                    onLog?.invoke("Handshake from ${session.remoteIpAddress}")
                    handleHandshake(session)
                }
                Constants.PATH_MEDIA_LIST -> handleList(session)
                Constants.PATH_MEDIA_FILE -> handleFile(session)
                Constants.PATH_REPLACE    -> handleReplace(session)
                Constants.PATH_FIX_DATES  -> handleFixDates(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (t: Throwable) {
            onLog?.invoke("Unhandled error in serve() [${session.uri}]: ${t.javaClass.simpleName}: ${t.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "Server error: ${t.javaClass.simpleName}: ${t.message}")
        }
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
        val files = mediaStore.getMediaSince(sinceSeconds)

        if (sinceSeconds > 0L) {
            // Download scan — reset upload session state
            stateSessionTotal = files.size
            stateSessionCurrent = 0
            stateCurrentFile = ""
            stateCurrentBytesRead = 0L
            stateCurrentFileTotal = 0L
            stateActive = files.isNotEmpty()
            sessionFileSizes = files.associate { it.id to it.size }
            if (files.isNotEmpty())
                onLog?.invoke("Hub requesting ${files.size} file(s) to download…")
        } else {
            // Compression scan — reset compression state
            stateCompressionTotal = files.count {
                it.mimeType.startsWith("image/") && it.mimeType != "image/gif"
            }
            stateCompressionDone = 0
            stateCompressionFile = ""
            onLog?.invoke("Hub scanning ${files.size} file(s) — ${stateCompressionTotal} image(s) eligible for compression…")
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

        // Update session state immediately so UI shows the file name at once
        stateSessionCurrent++
        stateCurrentFile = name
        stateCurrentFileTotal = fileSize
        stateCurrentBytesRead = 0L

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
        if (contentLength > 50 * 1024 * 1024L) return badRequest("Body too large (max 50 MB)")

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
            // Bump the total so the UI always shows X/N even when compression is inline
            // (hub no longer pre-announces total via since=0 for new files).
            if (stateCompressionDone >= stateCompressionTotal) stateCompressionTotal++
            val result = mediaStore.replaceFile(id, mime, bytes, dateTaken)
            stateCompressionDone++
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
            stateCompressionDone++
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
