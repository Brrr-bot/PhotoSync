package com.photosync.shared.model

data class HandshakeResponse(
    val accepted: Boolean,
    val deviceName: String
)
