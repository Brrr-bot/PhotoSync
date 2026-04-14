package com.photosync.hub.storage

import android.content.SharedPreferences

class SyncStateRepository(private val prefs: SharedPreferences) {

    /** Returns the last sync epoch millis for [mac], or 0 if never synced. */
    fun getLastSync(mac: String): Long = prefs.getLong(macKey(mac), 0L)

    fun updateLastSync(mac: String, timestampMs: Long) {
        prefs.edit().putLong(macKey(mac), timestampMs).apply()
    }

    /** Returns all known device MAC addresses (those synced at least once). */
    fun getKnownDevices(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX).replace('_', ':') }
            .toSet()
    }

    /** Returns display name stored for this MAC, if any. */
    fun getDeviceName(mac: String): String? =
        prefs.getString(nameKey(mac), null)

    fun setDeviceName(mac: String, name: String) {
        prefs.edit().putString(nameKey(mac), name).apply()
    }

    // ── Downloaded-file ID tracking ───────────────────────────────────────────
    // Tracks MediaStore IDs (as strings) from the phone that have been successfully
    // written to USB.  Provides a second dedup guard independent of filename matching.

    /** Returns the set of MediaStore file IDs already downloaded for [deviceName]. */
    fun getDownloadedIds(deviceName: String): Set<Long> =
        (prefs.getStringSet(downloadedKey(deviceName), emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toHashSet()

    /** Records that [id] was successfully written to USB for [deviceName]. */
    fun markDownloaded(deviceName: String, id: Long) {
        val existing = prefs.getStringSet(downloadedKey(deviceName), mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()
        existing.add(id.toString())
        prefs.edit().putStringSet(downloadedKey(deviceName), existing).apply()
    }

    /** Clears the downloaded-ID cache for [deviceName] (e.g. after a factory reset). */
    fun clearDownloadedIds(deviceName: String) {
        prefs.edit().remove(downloadedKey(deviceName)).apply()
    }

    /** Returns the set of filenames already compressed for this device. */
    fun getCompressedFiles(deviceName: String): Set<String> {
        return prefs.getStringSet(compressKey(deviceName), emptySet()) ?: emptySet()
    }

    /** Marks a filename as compressed. Appends to existing set. */
    fun markCompressed(deviceName: String, filename: String) {
        val existing = prefs.getStringSet(compressKey(deviceName), mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
        existing.add(filename)
        prefs.edit().putStringSet(compressKey(deviceName), existing).apply()
    }

    /**
     * Clears the compressed-files cache for [deviceName].
     * Next sync will re-read originals from USB and re-compress all images,
     * fixing any that were previously compressed with incorrect orientation.
     */
    fun clearCompressedFiles(deviceName: String) {
        prefs.edit().remove(compressKey(deviceName)).apply()
    }

    /** Clears compressed-files cache for ALL known devices. */
    fun clearAllCompressedFiles() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(COMPRESS_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // ── Date-check tracking ───────────────────────────────────────────────────
    // Tracks filenames whose DATE_TAKEN has already been verified/corrected,
    // so we don't re-check the same files on every sync.

    /** Returns the set of filenames whose dates have already been checked for [deviceName]. */
    fun getDateCheckedFiles(deviceName: String): Set<String> =
        prefs.getStringSet(dateCheckedKey(deviceName), emptySet()) ?: emptySet()

    /**
     * Marks [filenames] as date-checked for [deviceName].
     * Appends to any already-checked names — never shrinks the set.
     */
    fun markDateChecked(deviceName: String, filenames: Set<String>) {
        if (filenames.isEmpty()) return
        val existing = prefs.getStringSet(dateCheckedKey(deviceName), mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()
        existing.addAll(filenames)
        prefs.edit().putStringSet(dateCheckedKey(deviceName), existing).apply()
    }

    private fun macKey(mac: String)          = PREFIX + mac.replace(':', '_')
    private fun nameKey(mac: String)         = NAME_PREFIX + mac.replace(':', '_')
    private fun compressKey(deviceName: String) = COMPRESS_PREFIX + deviceName.replace(' ', '_')
    private fun downloadedKey(deviceName: String) = DOWNLOADED_PREFIX + deviceName.replace(' ', '_')
    private fun dateCheckedKey(deviceName: String) = DATE_CHECKED_PREFIX + deviceName.replace(' ', '_')

    companion object {
        private const val PREFIX              = "sync_ts_"
        private const val NAME_PREFIX         = "device_name_"
        private const val COMPRESS_PREFIX     = "compressed_"
        private const val DOWNLOADED_PREFIX   = "downloaded_"
        private const val DATE_CHECKED_PREFIX = "date_checked_"
    }
}
