package com.photosync.cloudsync.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One compressed item and a pointer back to its cloud original (for recall). */
data class ManifestEntry(
    val compressedName: String,
    val provider: String,
    val originalId: String,
    val originalName: String,
    val dateMs: Long,
    val sizeBytes: Long,
    val isVideo: Boolean,
)

/** Persisted index of every compressed copy and how to fetch its original. */
class CloudManifest(private val file: File) {
    private val entries = LinkedHashMap<String, ManifestEntry>()  // key = compressedName

    init { load() }

    @Synchronized fun add(e: ManifestEntry) { entries[e.compressedName] = e }
    @Synchronized fun all(): List<ManifestEntry> = entries.values.toList()
    @Synchronized fun size() = entries.size

    @Synchronized fun save() {
        val arr = JSONArray()
        for (e in entries.values) arr.put(JSONObject().apply {
            put("compressedName", e.compressedName)
            put("provider", e.provider)
            put("originalId", e.originalId)
            put("originalName", e.originalName)
            put("dateMs", e.dateMs)
            put("sizeBytes", e.sizeBytes)
            put("isVideo", e.isVideo)
        })
        try { file.parentFile?.mkdirs(); file.writeText(arr.toString()) } catch (_: Throwable) {}
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = ManifestEntry(
                    o.getString("compressedName"), o.getString("provider"), o.getString("originalId"),
                    o.optString("originalName", ""), o.optLong("dateMs", 0L), o.optLong("sizeBytes", 0L),
                    o.optBoolean("isVideo", false))
                entries[e.compressedName] = e
            }
        } catch (_: Throwable) {}
    }
}
