package com.photosync.client.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.photosync.client.util.RemoteLogger
import com.photosync.shared.Constants
import com.photosync.shared.crypto.HmacAuth
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks device location every minute.
 *
 * - Registers a LocationListener for fresh GPS/network fixes
 * - Logs each fix to RemoteLogger ([LOC] tag) so hub and tablet can read it
 * - Appends each fix to location_history.csv in app's external files dir
 * - POSTs each fix to hub /location endpoint for future timesheet processing
 *
 * Future: hub will compare fixes to known school locations in HCMC.
 * If device is at a school for ≥45 min, it will log arrival/departure + school name.
 */
class LocationTracker(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var bestLocation: Location? = null

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── CSV log file ──────────────────────────────────────────────────────────

    private val csvFile: File by lazy {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "location_history.csv").also { f ->
            if (!f.exists()) {
                f.writeText("timestamp_ms,datetime,lat,lng,accuracy_m,provider\n")
            }
        }
    }

    // ── Location listener ─────────────────────────────────────────────────────

    private val listener = LocationListener { loc ->
        val current = bestLocation
        if (current == null || loc.accuracy < current.accuracy) {
            bestLocation = loc
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun hasPermission(): Boolean {
        val fine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** Start listening for location updates. Call once from service onCreate. */
    fun start() {
        if (!hasPermission()) return
        try {
            // Request updates from every enabled provider; keep the best fix
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        30_000L,   // min 30s between updates
                        0f,        // min 0m distance
                        listener,
                        Looper.getMainLooper()
                    )
                }
            }
            // Seed with last known so first log isn't empty
            seedLastKnown()
        } catch (_: SecurityException) {}
    }

    /** Stop listening. Call from service onDestroy. */
    fun stop() {
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
    }

    // ── Main log-and-send (called every minute) ───────────────────────────────

    fun logAndSend(hubIp: String?, hubPort: Int) {
        if (!hasPermission()) return
        val loc = freshLocation() ?: return

        val ts      = System.currentTimeMillis()
        val dtStr   = dateFmt.format(Date(ts))
        val lat     = loc.latitude
        val lng     = loc.longitude
        val acc     = loc.accuracy
        val provider = loc.provider ?: "unknown"

        // 1. Log via RemoteLogger — visible in hub/tablet log stream
        RemoteLogger.i("[LOC] $dtStr  lat=$lat  lng=$lng  acc=${acc.toInt()}m  via=$provider")

        // 2. Append to local CSV
        try {
            csvFile.appendText("$ts,$dtStr,$lat,$lng,$acc,$provider\n")
        } catch (_: Exception) {}

        // 3. POST to hub (hub /location endpoint — for future school-matching logic)
        if (hubIp != null) {
            sendToHub(hubIp, hubPort, lat, lng, acc, provider, ts)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun seedLastKnown() {
        try {
            for (provider in locationManager.getProviders(true)) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                val cur = bestLocation
                if (cur == null || loc.accuracy < cur.accuracy) bestLocation = loc
            }
        } catch (_: SecurityException) {}
    }

    private fun freshLocation(): Location? {
        // Prefer a live fix received in the last 2 minutes
        val live = bestLocation
        if (live != null && System.currentTimeMillis() - live.time < 120_000L) return live
        // Fall back to last known
        seedLastKnown()
        return bestLocation
    }

    private fun sendToHub(ip: String, port: Int, lat: Double, lng: Double,
                          acc: Float, provider: String, ts: Long) {
        Thread {
            try {
                val device = Build.MODEL
                val hmac   = HmacAuth.sign(HmacAuth.buildPayload(ts, device))
                val json   = JSONObject().apply {
                    put("lat",      lat)
                    put("lng",      lng)
                    put("accuracy", acc)
                    put("provider", provider)
                    put("timestamp", ts)
                    put("device",   device)
                }.toString()
                val conn = URL("http://$ip:$port/location").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty(Constants.HEADER_HMAC,      hmac)
                conn.setRequestProperty(Constants.HEADER_TIMESTAMP,  ts.toString())
                conn.setRequestProperty(Constants.HEADER_DEVICE,     device)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}
