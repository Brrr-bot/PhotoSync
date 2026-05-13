package com.photosync.client.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.photosync.client.BuildConfig
import com.photosync.client.ClientApplication
import com.photosync.shared.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class VersionManifest(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("apkUrl")      val apkUrl: String = "",
    @SerializedName("clientVersionCode") val clientVersionCode: Int = 0,
    @SerializedName("clientVersionName") val clientVersionName: String = "",
    @SerializedName("clientApkUrl")      val clientApkUrl: String = "",
    @SerializedName("timesheetVersionCode") val timesheetVersionCode: Int = 0,
    @SerializedName("timesheetApkUrl")      val timesheetApkUrl: String = ""
)

class UpdateChecker(private val context: Context) {

    private val gson   = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkAndNotify() {
        val url = Constants.UPDATE_CHECK_URL.trim()
        if (url.isEmpty()) return
        val manifest = fetchManifest(url) ?: return
        val remoteCode = if (manifest.clientVersionCode > 0) manifest.clientVersionCode else manifest.versionCode
        if (remoteCode <= BuildConfig.VERSION_CODE) return
        if (notifiedPhotosync.getAndSet(remoteCode) == remoteCode) return  // already notified
        val apkFile = downloadApk(manifest) ?: return
        if (!silentInstall(apkFile)) {
            postInstallNotification(manifest, apkFile)
        }
    }

    private fun fetchManifest(url: String): VersionManifest? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                gson.fromJson(response.body?.string() ?: return null, VersionManifest::class.java)
            }
        } catch (_: Exception) { null }
    }

    private fun downloadApk(manifest: VersionManifest): File? {
        val apkUrl = manifest.clientApkUrl.ifBlank { manifest.apkUrl }
        if (apkUrl.isBlank()) return null
        return try {
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val remoteCode = if (manifest.clientVersionCode > 0) manifest.clientVersionCode else manifest.versionCode
                val outFile = File(context.cacheDir, "update-${remoteCode}.apk")
                outFile.writeBytes(bytes)
                outFile
            }
        } catch (_: Exception) { null }
    }

    private fun silentInstall(apkFile: File): Boolean {
        return try {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).also { p ->
                p.setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    p.setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                }
            }
            val sessionId = pi.createSession(params)
            pi.openSession(sessionId).use { session ->
                session.openWrite("package", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            true
        } catch (_: Exception) { false }
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
        val notif = NotificationCompat.Builder(context, ClientApplication.UPDATE_CHANNEL_ID)
            .setContentTitle("PhotoSync Client update available")
            .setContentText("Version ${manifest.versionName} ready — tap to install")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(ClientApplication.UPDATE_NOTIFICATION_ID, notif)
    }

    /** Check whether a newer Timesheet APK is available, download it, and show an install notification. */
    fun checkTimesheetUpdate() {
        val url = Constants.UPDATE_CHECK_URL.trim()
        if (url.isEmpty()) return
        val manifest = fetchManifest(url) ?: return
        val remoteCode = manifest.timesheetVersionCode
        if (remoteCode <= 0) return
        val apkUrl = manifest.timesheetApkUrl.ifBlank { return }

        val installedCode = try {
            val pi = context.packageManager.getPackageInfo("com.mcubi.timesheet", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) { 0 }

        if (remoteCode <= installedCode) return
        if (notifiedTimesheet.getAndSet(remoteCode) == remoteCode) return  // already notified

        // Download APK to cache then show a local-file install notification
        val apkFile = try {
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val bytes = response.body?.bytes() ?: return
                val out = File(context.cacheDir, "timesheet-update-$remoteCode.apk")
                out.writeBytes(bytes)
                out
            }
        } catch (_: Exception) { return }

        postTimesheetInstallNotification(remoteCode, apkFile)
    }

    private fun postTimesheetInstallNotification(remoteCode: Int, apkFile: File) {
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
            context, TIMESHEET_UPDATE_REQ_CODE, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, ClientApplication.UPDATE_CHANNEL_ID)
            .setContentTitle("Timesheet update ready")
            .setContentText("v$remoteCode downloaded — tap to install")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(TIMESHEET_UPDATE_NOTIF_ID, notif)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.photosync.client.INSTALL_STATUS"
        private const val TIMESHEET_UPDATE_NOTIF_ID  = 2010
        private const val TIMESHEET_UPDATE_REQ_CODE  = 2010

        /** Version codes we've already shown a notification for — prevents repeat firing. */
        private val notifiedPhotosync  = java.util.concurrent.atomic.AtomicInteger(0)
        private val notifiedTimesheet  = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
