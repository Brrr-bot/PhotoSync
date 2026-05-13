package com.photosync.client.service

import com.photosync.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on UDP [Constants.HUB_DISCOVERY_PORT] for hub announcements.
 * When a valid announcement is received, calls [onHubFound] with the hub's IP, HTTP port,
 * device name, and optional Tailscale IP.
 *
 * Message format: "PHOTOSYNC_HUB_HERE:{hubHttpPort}:{deviceName}"
 *             or: "PHOTOSYNC_HUB_HERE:{hubHttpPort}:{deviceName}:tsip:{hubTailscaleIp}"
 */
class HubDiscovery(
    private val onHubFound: (ip: String, port: Int, deviceName: String, tailscaleIp: String?) -> Unit
) {

    private var socket: DatagramSocket? = null

    suspend fun run() = withContext(Dispatchers.IO) {
        val buf = ByteArray(512)
        try {
            socket = DatagramSocket(Constants.HUB_DISCOVERY_PORT)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket!!.receive(packet)
                } catch (e: Exception) {
                    if (!isActive) break
                    continue
                }

                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!message.startsWith(Constants.HUB_DISCOVERY_PREFIX)) continue

                // Parse optional ":tsip:{ip}" trailer first, then strip it
                val tailscaleIp: String?
                val base: String
                val tsMarker = ":tsip:"
                if (message.contains(tsMarker)) {
                    val idx = message.lastIndexOf(tsMarker)
                    tailscaleIp = message.substring(idx + tsMarker.length).takeIf { it.isNotEmpty() }
                    base = message.substring(0, idx)
                } else {
                    tailscaleIp = null
                    base = message
                }

                // Parse "PHOTOSYNC_HUB_HERE:{port}:{deviceName}"
                val parts = base.split(":")
                if (parts.size < 3) continue
                val port = parts.getOrNull(1)?.toIntOrNull() ?: continue
                val deviceName = parts.drop(2).joinToString(":")
                val ip = packet.address.hostAddress ?: continue

                onHubFound(ip, port, deviceName, tailscaleIp)
            }
        } finally {
            socket?.close()
            socket = null
        }
    }

    fun stop() {
        socket?.close()
    }
}
