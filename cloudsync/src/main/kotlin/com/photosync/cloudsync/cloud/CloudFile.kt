package com.photosync.cloudsync.cloud

/** A photo/video discovered on a cloud drive. */
data class CloudFile(
    val provider: String,    // "google" / "onedrive"
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateMs: Long,        // best capture date (EXIF/taken/created), 0 if unknown
    val downloadUrl: String?, // pre-authenticated direct URL when the API supplies one (OneDrive)
)
