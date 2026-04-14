package com.photosync.hub.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.photosync.hub.BuildConfig
import com.photosync.hub.HubApplication
import com.photosync.shared.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class VersionManifest(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("apkUrl")      val apkUrl: String = ""
)

/**
 * Checks for a newer APK at [Constants.UPDATE_CHECK_URL] and, if found, downloads it
 * and posts a notification the user can tap to install.
 *
 * The version manifest JSON format:
 * ```json
 * { "versionCode": 2, "versionName": "1.1", "apkUrl": "https://example.com/photosync-hub.apk" }
 * ```
 *
 * Host this file anywhere reachable from the tablet — GitHub raw files, any web server,
 * or even a Tailscale-accessible URL on your own machine.
 */
class UpdateChecker(private val context: Context) {

    private val gson   = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Runs the full check-download-notify cycle.
     * Safe to call on a background thread; returns immediately if UPDATE_CHECK_URL is empty.
     */
    fun checkAndNotify() {
        val url = Constants.UPDATE_CHECK_URL.trim()
        if (url.isEmpty()) return

        val manifest = fetchManifest(url) ?: return
        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return   // already up to date

        val apkFile = downloadApk(manifest) ?: return
        postInstallNotification(manifest, apkFile)
    }

    private fun fetchManifest(url: String): VersionManifest? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                gson.fromJson(body, VersionManifest::class.java)
            }
        } catch (_: Exception) { null }
    }

    private fun downloadApk(manifest: VersionManifest): File? {
        if (manifest.apkUrl.isBlank()) return null
        return try {
            val request = Request.Builder().url(manifest.apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val outFile = File(context.cacheDir, "update-${manifest.versionCode}.apk")
                outFile.writeBytes(bytes)
                outFile
            }
        } catch (_: Exception) { null }
    }

    private fun postInstallNotification(manifest: VersionManifest, apkFile: File) {
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val tapIntent = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, HubApplication.UPDATE_CHANNEL_ID)
            .setContentTitle("PhotoSync Hub update available")
            .setContentText("Version ${manifest.versionName} ready — tap to install")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(HubApplication.UPDATE_NOTIFICATION_ID, notif)
    }
}
