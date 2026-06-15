package com.photosync.client.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.photosync.client.hub.HubFileEntry
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.util.RemoteLogger
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "Restore metadata from hub" — a CHECK-and-FIX pass, not a full rewrite.
 *
 *  • For every compressed photo whose original is on the hub, it compares the phone copy's current
 *    gallery date against the authoritative date (the YYYYMMDD in the filename — the same source the
 *    hub files the file under). If they already agree, the file is LEFT UNTOUCHED — no download, no
 *    rewrite.
 *  • Only files whose date is wrong/missing are re-fetched from the hub original and re-tagged.
 *
 * RESUMABLE: every file handled this run is remembered (SharedPrefs). If the hub drops mid-run it
 * pauses and keeps the progress + an "active" flag; ClientForegroundService re-launches it on the
 * next hub connect and it continues from where it stopped instead of starting over.
 */
class MetadataRestorer(private val context: Context) {

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun restore(
        ip: String, port: Int, deviceName: String,
        hubFiles: List<HubFileEntry>,
        progress: ((done: Int, total: Int, name: String) -> Unit)? = null
    ): Int {
        // Map each hub ORIGINAL by lowercase stem so a phone "foo.webp" resolves to hub "foo.jpg".
        val hubByStem = HashMap<String, String>()
        for (f in hubFiles) {
            if (f.deviceName != deviceName) continue
            if (f.displayName.substringAfterLast('.', "").lowercase() !in imageExts) continue
            val stem = f.displayName.substringBeforeLast('.').lowercase()
            val cur = hubByStem[stem]
            if (cur == null || (cur.endsWith(".webp", true) && !f.displayName.endsWith(".webp", true)))
                hubByStem[stem] = f.displayName
        }
        if (hubByStem.isEmpty()) return 0

        data class Local(val id: Long, val name: String, val hubName: String, val taken: Long, val addedSec: Long)
        val targets = ArrayList<Local>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iNm = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iDt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iDa = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val name = c.getString(iNm) ?: continue
                val hubName = hubByStem[name.substringBeforeLast('.').lowercase()] ?: continue
                targets.add(Local(c.getLong(iId), name, hubName, c.getLong(iDt), c.getLong(iDa)))
            }
        }
        if (targets.isEmpty()) return 0

        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
        val done = prefs.getStringSet(KEY_DONE, emptySet())!!.toMutableSet()
        val remaining = targets.filter { it.name !in done }
        RemoteLogger.i("↺ Restore from hub: ${remaining.size} to check (${done.size} already done this run)…")

        val store = MediaStoreHelper(context)
        var fixed = 0; var checked = 0; var alreadyOk = 0
        var consecutiveHubFails = 0
        var paused = false
        try {
            remaining.forEachIndexed { index, t ->
                progress?.invoke(index + 1, remaining.size, t.name)
                checked++
                try {
                    // CHEAP CHECK: is the phone copy's date already correct (matches the filename date
                    // the hub files it under)? If so, leave it completely alone — no download.
                    val expected = parseDateFromName(t.name)
                    val current = if (t.taken > 0) t.taken else t.addedSec * 1000L
                    if (expected > 0 && current > 0 && Math.abs(current - expected) <= TWO_DAYS) {
                        alreadyOk++; done.add(t.name)
                        if (done.size % 25 == 0) persist(done)
                        return@forEachIndexed
                    }

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, t.id)
                    val localBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: run { done.add(t.name); return@forEachIndexed }
                    if (!isWebp(localBytes)) { done.add(t.name); return@forEachIndexed }

                    val original = HubFilesClient.fetchFile(ip, port, deviceName, t.hubName)
                    if (original == null) {
                        if (++consecutiveHubFails >= 8) throw IllegalStateException("hub unreachable")
                        return@forEachIndexed   // not marked done → retried next run
                    }
                    consecutiveHubFails = 0
                    if (original.isEmpty() || original.size > MAX_ORIGINAL_BYTES) { done.add(t.name); return@forEachIndexed }
                    val webp = WebPConverter.convert(original, context.cacheDir) ?: run { done.add(t.name); return@forEachIndexed }
                    if (webp.size >= original.size) { done.add(t.name); return@forEachIndexed }
                    store.replaceFile(t.id, "image/webp", webp, t.taken)
                    fixed++; done.add(t.name)
                    if (done.size % 25 == 0) persist(done)
                    RemoteLogger.i("↺ ${t.name}  date was off — re-tagged from hub original")
                    if (fixed % 10 == 0) Thread.sleep(200)
                } catch (th: Throwable) {
                    if (th is IllegalStateException && th.message == "hub unreachable") throw th
                    done.add(t.name)   // skip a bad file so it can't wedge the run
                }
            }
        } catch (stop: IllegalStateException) {
            paused = true
        }

        persist(done)
        if (paused) {
            RemoteLogger.i("⏸ Restore paused (hub unreachable) — will resume on reconnect; ${done.size} checked so far")
        } else {
            prefs.edit().putBoolean(KEY_ACTIVE, false).remove(KEY_DONE).apply()  // fresh next time
            RemoteLogger.i("✓ Restore done — checked $checked, fixed $fixed, already-correct $alreadyOk")
        }
        return fixed
    }

    private fun persist(done: Set<String>) { prefs.edit().putStringSet(KEY_DONE, done.toSet()).apply() }

    /** Parses an explicit YYYYMMDD[_-HHMMSS] date from a filename (no regex → no escaping pitfalls). */
    private fun parseDateFromName(name: String): Long {
        val s = name.substringBeforeLast('.')
        var i = 0
        while (i + 8 <= s.length) {
            if (s[i] == '2' && s[i + 1] == '0' && allDigits(s, i, 8)) {
                val y = s.substring(i, i + 4).toInt()
                val mo = s.substring(i + 4, i + 6).toInt()
                val da = s.substring(i + 6, i + 8).toInt()
                if (y in 2000..2035 && mo in 1..12 && da in 1..31) {
                    var pattern = "yyyyMMdd"
                    var datestr = s.substring(i, i + 8)
                    if (i + 15 <= s.length && (s[i + 8] == '_' || s[i + 8] == '-') && allDigits(s, i + 9, 6)) {
                        datestr = s.substring(i, i + 8) + s.substring(i + 9, i + 15)
                        pattern = "yyyyMMddHHmmss"
                    }
                    return try { SimpleDateFormat(pattern, Locale.US).parse(datestr)?.time ?: 0L } catch (_: Exception) { 0L }
                }
            }
            i++
        }
        return 0L
    }

    private fun allDigits(s: String, off: Int, n: Int): Boolean {
        if (off + n > s.length) return false
        for (k in off until off + n) if (!s[k].isDigit()) return false
        return true
    }

    private fun isWebp(b: ByteArray): Boolean =
        b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
        b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
        b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    companion object {
        private const val PREFS = "metadata_restore_state"
        const val KEY_ACTIVE = "active"
        private const val KEY_DONE = "done"
        private const val MAX_ORIGINAL_BYTES = 50L * 1024 * 1024
        private const val TWO_DAYS = 2L * 24 * 60 * 60 * 1000

        /** True if a restore was interrupted and should be auto-resumed on the next hub connect. */
        fun isIncomplete(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
    }
}
