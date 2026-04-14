package com.photosync.hub.service

import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import fi.iki.elonen.NanoHTTPD

/**
 * Lightweight HTTP server running on the hub ([Constants.HUB_HTTP_PORT]).
 * The phone calls POST /sync to ask the hub to pull its files immediately,
 * rather than waiting for the periodic UDP discovery cycle.
 *
 * @param onSyncRequest Called with the requester's IP when a valid /sync request arrives.
 */
class HubHttpServer(
    private val onSyncRequest: (clientIp: String) -> Unit,
    private val onLog: ((String) -> Unit)? = null
) : NanoHTTPD(Constants.HUB_HTTP_PORT) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                Constants.PATH_SYNC -> handleSync(session)
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

    private fun verifyHmacFromHeaders(session: IHTTPSession): Boolean {
        val hmac   = session.headers[Constants.HEADER_HMAC.lowercase()] ?: return false
        val tsStr  = session.headers[Constants.HEADER_TIMESTAMP.lowercase()] ?: return false
        val device = session.headers[Constants.HEADER_DEVICE.lowercase()] ?: return false
        val ts = tsStr.toLongOrNull() ?: return false
        return HmacAuth.verify(HmacAuth.buildPayload(ts, device), hmac, timestampMs = ts)
    }
}
