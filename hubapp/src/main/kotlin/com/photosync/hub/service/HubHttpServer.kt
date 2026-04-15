package com.photosync.hub.service

import com.google.gson.Gson
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import com.photosync.shared.model.DashboardStatusResponse
import fi.iki.elonen.NanoHTTPD

class HubHttpServer(
    private val onSyncRequest: (clientIp: String) -> Unit,
    private val onDashboardRequest: () -> DashboardStatusResponse,
    private val onLog: ((String) -> Unit)? = null
) : NanoHTTPD(Constants.HUB_HTTP_PORT) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                Constants.PATH_SYNC -> handleSync(session)
                Constants.PATH_DASHBOARD -> handleDashboard(session)
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

    private fun verifyHmacFromHeaders(session: IHTTPSession): Boolean {
        val hmac = session.headers[Constants.HEADER_HMAC.lowercase()] ?: return false
        val tsStr = session.headers[Constants.HEADER_TIMESTAMP.lowercase()] ?: return false
        val device = session.headers[Constants.HEADER_DEVICE.lowercase()] ?: return false
        val ts = tsStr.toLongOrNull() ?: return false
        return HmacAuth.verify(HmacAuth.buildPayload(ts, device), hmac, timestampMs = ts)
    }
}
