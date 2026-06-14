package com.photosync.cloudsync.cloud

import com.photosync.cloudsync.auth.DeviceCodeAuth

/** A cloud drive CloudSync can enumerate, download from, and authenticate against. */
interface CloudProvider {
    val key: String      // "google" / "onedrive"
    val label: String

    fun isConfigured(): Boolean
    fun isAuthed(): Boolean

    /** Start device-code auth — returns the code/URL to show the user, or null on failure. */
    fun beginAuth(): DeviceCodeAuth.DeviceCode?
    /** Block until the user authorises (or timeout); persists tokens. */
    fun awaitAuth(dc: DeviceCodeAuth.DeviceCode): Boolean

    /** Enumerate every photo/video, calling [onBatch] per page. Returns false on hard failure. */
    fun listAllMedia(onBatch: (List<CloudFile>) -> Unit): Boolean

    /** Download a file's original bytes, or null on failure. */
    fun download(file: CloudFile): ByteArray?
}
