package com.photosync.hub.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.photosync.hub.BuildConfig
import com.photosync.hub.HubApplication
import com.photosync.shared.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        context.getSharedPreferences("update_checker", Context.MODE_PRIVATE)
    }

    fun checkAndNotify() {
        val baseUrl = Constants.UPDATE_PORTAL_URL.trim()
        if (baseUrl.isEmpty()) return

        val info = fetchVersionInfo("$baseUrl/api/version/hub") ?: return
        val remoteCode = info.optInt("versionCode", 0)
        val apkUrl     = info.optString("apkUrl", "")
        val versionName = info.optString("versionName", "")

        if (remoteCode <= BuildConfig.VERSION_CODE || apkUrl.isEmpty()) {
            prefs.edit().remove(PREF_PENDING_VERSION).apply()
            return
        }

        val pendingVersion = prefs.getInt(PREF_PENDING_VERSION, 0)
        if (pendingVersion >= remoteCode) return  // already attempted this version

        val apkFile = downloadApk(apkUrl, "hub-$remoteCode.apk") ?: return
        if (silentInstall(apkFile)) {
            prefs.edit().putInt(PREF_PENDING_VERSION, remoteCode).apply()
        } else {
            postInstallNotification(
                title   = "PhotoSync Hub update available",
                body    = "Version $versionName ready — tap to install",
                apkFile = apkFile
            )
        }
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

    private fun postInstallNotification(title: String, body: String, apkFile: File) {
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
            context, apkFile.name.hashCode(), installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, HubApplication.UPDATE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(apkFile.name.hashCode(), notif)
    }

    companion object {
        const val ACTION_INSTALL_STATUS       = "com.photosync.hub.INSTALL_STATUS"
        private const val PREF_PENDING_VERSION = "pending_install_version"
    }
}
