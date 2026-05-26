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
import android.util.Log
import com.photosync.client.BuildConfig
import com.photosync.client.ClientApplication
import com.photosync.client.util.RemoteLogger
import com.photosync.shared.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkAndNotify() {
        val baseUrl = Constants.UPDATE_PORTAL_URL.trim()
        if (baseUrl.isEmpty()) return

        val info = fetchVersionInfo("$baseUrl/api/version/client") ?: run {
            Log.w(TAG, "checkAndNotify: portal unreachable"); return
        }
        val remoteCode  = info.optInt("versionCode", 0)
        val apkUrl      = info.optString("apkUrl", "")
        val versionName = info.optString("versionName", "")

        Log.d(TAG, "checkAndNotify: local=${BuildConfig.VERSION_CODE} remote=$remoteCode")
        RemoteLogger.i("OTA check: local v${BuildConfig.VERSION_CODE} remote v$remoteCode")

        if (remoteCode <= BuildConfig.VERSION_CODE || apkUrl.isEmpty()) return
        if (notifiedPhotosync.getAndSet(remoteCode) == remoteCode) return

        Log.i(TAG, "Downloading update v$versionName…")
        RemoteLogger.i("OTA: downloading client v$versionName")
        val apkFile = downloadApk(apkUrl, "client-$remoteCode.apk") ?: run {
            Log.e(TAG, "APK download failed")
            RemoteLogger.e("OTA: download failed, will retry next poll")
            notifiedPhotosync.set(0)
            return
        }
        RemoteLogger.i("OTA: download complete, showing notification")
        postInstallNotification(
            title   = "PhotoSync Client update available",
            body    = "Version $versionName ready — tap to install",
            apkFile = apkFile,
            notifId = ClientApplication.UPDATE_NOTIFICATION_ID
        )
    }

    fun checkTimesheetUpdate() {
        val baseUrl = Constants.UPDATE_PORTAL_URL.trim()
        if (baseUrl.isEmpty()) return

        val info = fetchVersionInfo("$baseUrl/api/version/timesheet") ?: return
        val remoteCode = info.optInt("versionCode", 0)
        val apkUrl     = info.optString("apkUrl", "")
        if (remoteCode <= 0 || apkUrl.isBlank()) return

        val installedCode = try {
            val pi = context.packageManager.getPackageInfo("com.mcubi.timesheet", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) { 0 }

        if (remoteCode <= installedCode) return
        if (notifiedTimesheet.getAndSet(remoteCode) == remoteCode) return

        val apkFile = downloadApk(apkUrl, "timesheet-$remoteCode.apk") ?: run {
            notifiedTimesheet.set(0); return
        }
        postInstallNotification(
            title   = "Timesheet update ready",
            body    = "v$remoteCode downloaded — tap to install",
            apkFile = apkFile,
            notifId = TIMESHEET_UPDATE_NOTIF_ID
        )
    }

    private fun fetchVersionInfo(url: String): JSONObject? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string() ?: return null)
            }
        } catch (_: Exception) { null }
    }

    private fun downloadApk(apkUrl: String, fileName: String): File? {
        if (apkUrl.isBlank()) return null
        return try {
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val outFile = File(context.cacheDir, fileName)
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

    private fun postInstallNotification(title: String, body: String, apkFile: File, notifId: Int) {
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
            context, notifId, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, ClientApplication.UPDATE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notif)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.photosync.client.INSTALL_STATUS"
        private const val TAG = "PhotoSyncOTA"
        private const val TIMESHEET_UPDATE_NOTIF_ID = 2010

        private val notifiedPhotosync = AtomicInteger(0)
        private val notifiedTimesheet = AtomicInteger(0)
    }
}
