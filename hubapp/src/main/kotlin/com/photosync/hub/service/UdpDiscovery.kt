package com.photosync.hub.service

import com.photosync.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on UDP [Constants.DISCOVERY_PORT] for PhotoSync client announcements.
 * When a valid announcement is received, calls [onClientFound] with the sender's IP.
 *
 * Message format expected: "PHOTOSYNC_HERE:{port}:{deviceName}"
 */
class UdpDiscovery(
    private val onClientFound: (ip: String, deviceName: String) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {

    private var socket: DatagramSocket? = null

    /**
     * Blocks listening for broadcasts until the coroutine is cancelled.
     * Run this on Dispatchers.IO.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        val buf = ByteArray(512)
        try {
            socket = DatagramSocket(Constants.DISCOVERY_PORT)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket!!.receive(packet)  // blocks until a packet arrives
                } catch (e: Exception) {
                    if (!isActive) break
                    onError?.invoke("Socket receive error: ${e.message}")
                    continue
                }

                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val ip = packet.address.hostAddress ?: continue

                if (!message.startsWith(Constants.DISCOVERY_PREFIX)) continue

                // Parse "PHOTOSYNC_HERE:{port}:{deviceName}"
                val parts = message.split(":")
                if (parts.size < 3) continue
                val deviceName = parts.drop(2).joinToString(":") // handle colons in device name

                onClientFound(ip, deviceName)
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
