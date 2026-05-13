package com.photosync.hub.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
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
    // PhotoSync Hub fields
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("apkUrl")      val apkUrl: String = "",
    // IPCam fields
    @SerializedName("ipcamVersionCode") val ipcamVersionCode: Int = 0,
    @SerializedName("ipcamVersionName") val ipcamVersionName: String = "",
    @SerializedName("ipcamApkUrl")      val ipcamApkUrl: String = ""
)

class UpdateChecker(private val context: Context) {

    private val gson   = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        context.getSharedPreferences("update_checker", Context.MODE_PRIVATE)
    }

    fun checkAndNotify() {
        val url = Constants.UPDATE_CHECK_URL.trim()
        if (url.isEmpty()) return
        val manifest = fetchManifest(url) ?: return

        // ── PhotoSync Hub self-update ────────────────────────────────────────
        if (manifest.versionCode > BuildConfig.VERSION_CODE) {
            val pendingVersion = prefs.getInt(PREF_PENDING_VERSION, 0)
            if (pendingVersion < manifest.versionCode) {
                val apkFile = downloadApk(manifest.apkUrl, "hub-${manifest.versionCode}.apk")
                if (apkFile != null) {
                    if (silentInstall(apkFile)) {
                        prefs.edit().putInt(PREF_PENDING_VERSION, manifest.versionCode).apply()
                    } else {
                        postInstallNotification(
                            title    = "PhotoSync Hub update available",
                            body     = "Version ${manifest.versionName} ready — tap to install",
                            apkFile  = apkFile
                        )
                    }
                }
            }
        } else {
            prefs.edit().remove(PREF_PENDING_VERSION).apply()
        }

        // ── IPCam companion update ───────────────────────────────────────────
        if (manifest.ipcamVersionCode > 0 && manifest.ipcamApkUrl.isNotBlank()) {
            val ipcamInstalled = runCatching {
                context.packageManager.getPackageInfo("com.ipcam", 0).longVersionCode.toInt()
            }.getOrDefault(-1)

            if (manifest.ipcamVersionCode > ipcamInstalled) {
                val pendingIpcam = prefs.getInt(PREF_PENDING_IPCAM_VERSION, 0)
                if (pendingIpcam < manifest.ipcamVersionCode) {
                    val apkFile = downloadApk(manifest.ipcamApkUrl, "ipcam-${manifest.ipcamVersionCode}.apk")
                    if (apkFile != null) {
                        if (silentInstall(apkFile)) {
                            prefs.edit().putInt(PREF_PENDING_IPCAM_VERSION, manifest.ipcamVersionCode).apply()
                        } else {
                            postInstallNotification(
                                title   = "IPCam update available",
                                body    = "Version ${manifest.ipcamVersionName} ready — tap to install",
                                apkFile = apkFile
                            )
                        }
                    }
                }
            } else {
                prefs.edit().remove(PREF_PENDING_IPCAM_VERSION).apply()
            }
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

    /**
     * Installs the APK via PackageInstaller without requiring a tap.
     * On Android 12+ (API 31) sets USER_ACTION_NOT_REQUIRED for a fully silent install.
     * Returns true if the install session was committed successfully.
     */
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
        private const val PREF_PENDING_VERSION       = "pending_install_version"
        private const val PREF_PENDING_IPCAM_VERSION = "pending_ipcam_install_version"
    }
}
