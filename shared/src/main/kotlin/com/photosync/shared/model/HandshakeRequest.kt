package com.photosync.shared.model

data class HandshakeRequest(
    val deviceName: String,
    val timestamp: Long,
    /** Hub's Tailscale IP (100.x.x.x) so the phone can store it for remote sync.
     *  Null when Tailscale is not installed on the hub. */
    val hubTailscaleIp: String? = null
)
