package com.photosync.shared.model

data class MediaFileInfo(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val size: Long,
    // DATE_ADDED from MediaStore is in seconds — stored here as seconds too
    val dateAdded: Long,
    // DATE_TAKEN from MediaStore is in milliseconds (from EXIF); 0 if not available
    val dateTaken: Long = 0L
)
