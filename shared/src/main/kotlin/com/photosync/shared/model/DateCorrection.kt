package com.photosync.shared.model

data class DateCorrection(
    val displayName: String,
    val correctDateTaken: Long   // epoch milliseconds
)
