package com.photosync.hub.service

import android.os.Build
import com.photosync.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends a UDP broadcast every [Constants.ANNOUNCE_INTERVAL_MS] so phones on the
 * same network can discover the hub's IP and HTTP port.
 *
 * Message format: "PHOTOSYNC_HUB_HERE:{hubHttpPort}:{deviceName}"
 */
class HubBroadcastAnnouncer {

    private val message = "${Constants.HUB_DISCOVERY_PREFIX}:${Constants.HUB_HTTP_PORT}:${Build.MODEL}"
        .toByteArray(Charsets.UTF_8)

    suspend fun run() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val packet = DatagramPacket(
                        message,
                        message.size,
                        InetAddress.getByName("255.255.255.255"),
                        Constants.HUB_DISCOVERY_PORT
                    )
                    socket.send(packet)
                }
            } catch (_: Exception) {
                // Network not ready — try again next interval
            }
            delay(Constants.ANNOUNCE_INTERVAL_MS)
        }
    }
}
