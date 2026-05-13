package com.photosync.client.network

import java.net.NetworkInterface

/**
 * Detects the Tailscale VPN IP address assigned to this device.
 * Tailscale IPs are in the CGNAT range 100.64.0.0/10 (100.64.x.x – 100.127.x.x).
 * Returns null if Tailscale is not installed or not connected.
 */
object TailscaleIpDetector {

    fun getIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filter { !it.isLoopbackAddress }
                ?.map { it.hostAddress ?: "" }
                ?.firstOrNull { isTailscaleIp(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Tailscale uses the CGNAT block: 100.64.0.0/10 → first octet 100, second 64–127. */
    private fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }
}
