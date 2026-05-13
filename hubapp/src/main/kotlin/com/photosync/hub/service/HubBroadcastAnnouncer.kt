package com.photosync.hub.service

import android.os.Build
import com.photosync.hub.network.TailscaleIpDetector
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
 *   • Unicast to every known client Tailscale IP for cross-network discovery
 *
 * Message format: "PHOTOSYNC_HUB_HERE:{port}:{deviceName}:tsip:{hubTailscaleIp}"
 * The ":tsip:" suffix is optional — clients without Tailscale simply don't include it.
 */
class HubBroadcastAnnouncer(
    /** Returns the current list of client Tailscale IPs to unicast to. */
    private val knownClientTailscaleIps: () -> List<String> = { emptyList() }
) {

    suspend fun run() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                val tsIp    = TailscaleIpDetector.getIp()
                val trailer = if (tsIp != null) ":tsip:$tsIp" else ""
                val payload = "${Constants.HUB_DISCOVERY_PREFIX}:${Constants.HUB_HTTP_PORT}:${Build.MODEL}$trailer"
                    .toByteArray(Charsets.UTF_8)

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    // ── Local-network broadcast ───────────────────────────────
                    socket.send(DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName("255.255.255.255"),
                        Constants.HUB_DISCOVERY_PORT
                    ))
                    // ── Tailscale unicast to every known client ────────────────
                    for (clientIp in knownClientTailscaleIps()) {
                        try {
                            socket.send(DatagramPacket(
                                payload, payload.size,
                                InetAddress.getByName(clientIp),
                                Constants.HUB_DISCOVERY_PORT
                            ))
                        } catch (_: Exception) { /* unreachable client — skip */ }
                    }
                }
            } catch (_: Exception) {
                // Network not ready — try again next interval
            }
            delay(Constants.ANNOUNCE_INTERVAL_MS)
        }
    }
}
