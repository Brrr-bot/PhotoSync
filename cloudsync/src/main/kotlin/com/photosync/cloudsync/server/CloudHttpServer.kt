package com.photosync.cloudsync.server

import com.photosync.cloudsync.CloudConfig
import com.photosync.cloudsync.cloud.CloudFile
import com.photosync.cloudsync.cloud.CloudProvider
import com.photosync.cloudsync.sync.CloudManifest
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Local HTTP server the phone pulls from:
 *   GET /cloud/manifest          → JSON list of compressed items (+ provider/id/date)
 *   GET /cloud/thumb?name=...     → the compressed copy bytes (webp/jpg)
 *   GET /cloud/original?provider=&id=  → downloads + streams the ORIGINAL from the cloud (recall)
 */
class CloudHttpServer(
    private val outputDir: File,
    private val manifest: CloudManifest,
    private val providers: List<CloudProvider>,
) : NanoHTTPD(CloudConfig.HTTP_PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri) {
            "/cloud/manifest" -> manifestResponse()
            "/cloud/thumb"    -> thumbResponse(session)
            "/cloud/original" -> originalResponse(session)
            "/", "/status"    -> newFixedLengthResponse(Response.Status.OK, "text/plain",
                "CloudSync running. ${manifest.size()} compressed items.")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    } catch (t: Throwable) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${t.message}")
    }

    private fun manifestResponse(): Response {
        val arr = JSONArray()
        for (e in manifest.all()) arr.put(JSONObject().apply {
            put("name", e.compressedName); put("provider", e.provider); put("id", e.originalId)
            put("originalName", e.originalName); put("dateMs", e.dateMs); put("isVideo", e.isVideo)
        })
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun thumbResponse(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing name")
        val f = File(outputDir, name)
        if (!f.exists() || !f.canonicalPath.startsWith(outputDir.canonicalPath))
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        val mime = if (name.endsWith(".webp")) "image/webp" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, mime, f.inputStream(), f.length())
    }

    private fun originalResponse(session: IHTTPSession): Response {
        val provider = session.parameters["provider"]?.firstOrNull()
        val id = session.parameters["id"]?.firstOrNull()
        if (provider == null || id == null)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing provider/id")
        val p = providers.firstOrNull { it.key == provider }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no provider")
        val entry = manifest.all().firstOrNull { it.provider == provider && it.originalId == id }
        val cf = CloudFile(provider, id, entry?.originalName ?: id,
            if (entry?.isVideo == true) "video/" else "image/", 0L, 0L, null)
        val bytes = p.download(cf)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "download failed")
        val mime = if (entry?.isVideo == true) "video/mp4" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }
}
