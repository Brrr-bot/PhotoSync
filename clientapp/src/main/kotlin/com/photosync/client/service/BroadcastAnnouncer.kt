package com.photosync.client.service

import android.os.Build
import com.photosync.client.network.TailscaleIpDetector
import com.photosync.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends a UDP announcement every [Constants.ANNOUNCE_INTERVAL_MS]:
 *   • Broadcast on 255.255.255.255 for same-network discovery
 *   • Unicast to the hub's Tailscale IP for cross-network discovery
 *
 * Message format: "PHOTOSYNC_HERE:{port}:{deviceName}:tsip:{clientTailscaleIp}"
 * The ":tsip:" suffix is omitted if the client has no Tailscale IP.
 */
class BroadcastAnnouncer(
    /** Returns the hub's Tailscale IP to unicast to, or null if unknown. */
    private val hubTailscaleIpProvider: () -> String? = { null }
) {

    /**
     * Loops forever broadcasting until the coroutine is cancelled.
     * Run this inside a coroutine on Dispatchers.IO.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                val tsIp    = TailscaleIpDetector.getIp()
                val trailer = if (tsIp != null) ":tsip:$tsIp" else ""
                val payload = "${Constants.DISCOVERY_PREFIX}:${Constants.CLIENT_PORT}:${Build.MODEL}$trailer"
                    .toByteArray(Charsets.UTF_8)

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    // ── Local-network broadcast ───────────────────────────────
                    socket.send(DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName("255.255.255.255"),
                        Constants.DISCOVERY_PORT
                    ))
                    // ── Tailscale unicast to hub ──────────────────────────────
                    val hubIp = hubTailscaleIpProvider()
                    if (hubIp != null) {
                        try {
                            socket.send(DatagramPacket(
                                payload, payload.size,
                                InetAddress.getByName(hubIp),
                                Constants.DISCOVERY_PORT
                            ))
                        } catch (_: Exception) { /* hub unreachable over Tailscale — skip */ }
                    }
                }
            } catch (_: Exception) {
                // Network not ready yet — try again next interval
            }
            delay(Constants.ANNOUNCE_INTERVAL_MS)
        }
    }
}
