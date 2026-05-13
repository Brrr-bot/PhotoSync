package com.photosync.hub.service

import com.photosync.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on UDP [Constants.DISCOVERY_PORT] for PhotoSync client announcements.
 * Accepts both local-network broadcasts and Tailscale unicast packets.
 *
 * Message format: "PHOTOSYNC_HERE:{port}:{deviceName}" or
 *                 "PHOTOSYNC_HERE:{port}:{deviceName}:tsip:{clientTailscaleIp}"
 */
class UdpDiscovery(
    /** Called with the sender's IP, device name, and optional Tailscale IP. */
    private val onClientFound: (ip: String, deviceName: String, tailscaleIp: String?) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {

    private var socket: DatagramSocket? = null

    suspend fun run() = withContext(Dispatchers.IO) {
        val buf = ByteArray(512)
        try {
            socket = DatagramSocket(Constants.DISCOVERY_PORT)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket!!.receive(packet)
                } catch (e: Exception) {
                    if (!isActive) break
                    onError?.invoke("Socket receive error: ${e.message}")
                    continue
                }

                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val senderIp = packet.address.hostAddress ?: continue

                if (!message.startsWith(Constants.DISCOVERY_PREFIX)) continue

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

                // Parse "PHOTOSYNC_HERE:{port}:{deviceName}"
                val parts = base.split(":")
                if (parts.size < 3) continue
                val deviceName = parts.drop(2).joinToString(":")

                // Prefer the sender's Tailscale IP if the packet arrived over Tailscale
                // (100.64-127.x.x.x), otherwise use whatever the packet reports
                val effectiveIp = if (isTailscaleIp(senderIp)) senderIp else senderIp
                onClientFound(effectiveIp, deviceName, tailscaleIp)
            }
        } finally {
            socket?.close()
            socket = null
        }
    }

    fun stop() {
        socket?.close()
    }

    private fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }
}
