package com.photosync.client.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LocationTracker(private val context: Context) {

    fun getAndSend(hubIp: String, hubPort: Int) {
        if (!hasPermission()) return
        val location = getBestLastLocation() ?: return
        sendToHub(hubIp, hubPort, location)
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun getBestLastLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in lm.getProviders(true)) {
            val loc = try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
                ?: continue
            if (best == null || loc.accuracy < best.accuracy) best = loc
        }
        return best
    }

    private fun sendToHub(ip: String, port: Int, location: Location) {
        try {
            val ts = System.currentTimeMillis()
            val device = Build.MODEL
            val hmac = HmacAuth.sign(HmacAuth.buildPayload(ts, device))
            val json = JSONObject().apply {
                put("lat", location.latitude)
                put("lng", location.longitude)
                put("accuracy", location.accuracy)
                put("timestamp", ts)
                put("device", device)
            }.toString()
            val conn = URL("http://$ip:$port/location").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty(Constants.HEADER_HMAC, hmac)
            conn.setRequestProperty(Constants.HEADER_TIMESTAMP, ts.toString())
            conn.setRequestProperty(Constants.HEADER_DEVICE, device)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.doOutput = true
            conn.outputStream.use { it.write(json.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
