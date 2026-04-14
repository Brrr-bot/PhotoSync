package com.photosync.client.service

import com.photosync.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on UDP [Constants.HUB_DISCOVERY_PORT] for hub announcements.
 * When a valid announcement is received, calls [onHubFound] with the hub's IP and HTTP port.
 *
 * Message format: "PHOTOSYNC_HUB_HERE:{hubHttpPort}:{deviceName}"
 */
class HubDiscovery(
    private val onHubFound: (ip: String, port: Int, deviceName: String) -> Unit
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

                // Parse "PHOTOSYNC_HUB_HERE:{port}:{deviceName}"
                val parts = message.split(":")
                if (parts.size < 3) continue
                val port = parts.getOrNull(1)?.toIntOrNull() ?: continue
                val deviceName = parts.drop(2).joinToString(":")
                val ip = packet.address.hostAddress ?: continue

                onHubFound(ip, port, deviceName)
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
