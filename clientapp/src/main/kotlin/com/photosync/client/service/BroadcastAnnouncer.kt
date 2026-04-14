package com.photosync.client.service

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
 * Sends a UDP broadcast every [Constants.ANNOUNCE_INTERVAL_MS] so the hub
 * can discover this device on the local network without relying on mDNS.
 *
 * Message format: "PHOTOSYNC_HERE:{port}:{deviceName}"
 */
class BroadcastAnnouncer {

    private val message = "${Constants.DISCOVERY_PREFIX}:${Constants.CLIENT_PORT}:${Build.MODEL}"
        .toByteArray(Charsets.UTF_8)

    /**
     * Loops forever broadcasting until the coroutine is cancelled.
     * Run this inside a coroutine on Dispatchers.IO.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val packet = DatagramPacket(
                        message,
                        message.size,
                        InetAddress.getByName("255.255.255.255"),
                        Constants.DISCOVERY_PORT
                    )
                    socket.send(packet)
                }
            } catch (_: Exception) {
                // Network not ready yet — try again next interval
            }
            delay(Constants.ANNOUNCE_INTERVAL_MS)
        }
    }
}
