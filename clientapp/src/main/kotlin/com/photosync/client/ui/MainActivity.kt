package com.photosync.client.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.photosync.client.R
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.service.ClientForegroundService
import com.photosync.client.update.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var pbSync: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var pbFile: ProgressBar
    private lateinit var tvMbRemaining: TextView
    private lateinit var btnMenu: ImageButton
    private lateinit var pbCompression: ProgressBar
    private lateinit var tvCompressionLabel: TextView
    private lateinit var tvHubStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView

    private val logLines = ArrayDeque<String>(100)

    // ── Polling ───────────────────────────────────────────────────────────────
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollServerState()
            pollHandler.postDelayed(this, 200)
        }
    }

    private fun pollServerState() {
        val srv = ClientForegroundService.liveServer ?: return

        // ── Upload session bar ────────────────────────────────────────────────
        val sesTotal   = srv.stateSessionTotal
        val sesCurrent = srv.stateSessionCurrent
        if (sesTotal > 0) {
            pbSync.max = sesTotal
            pbSync.progress = sesCurrent
            tvProgressLabel.text = "$sesCurrent / $sesTotal"
        }

        // ── Current file bar ──────────────────────────────────────────────────
        val fileTotal = srv.stateCurrentFileTotal
        val bytesRead = srv.stateCurrentBytesRead
        val filename  = srv.stateCurrentFile
        if (fileTotal > 0 && filename.isNotEmpty()) {
            tvCurrentFile.text = "$filename  (${formatBytes(fileTotal)})"
            pbFile.visibility = View.VISIBLE
            pbFile.max = 10000
            pbFile.progress = ((bytesRead * 10000L) / fileTotal).toInt()
            val remaining = (fileTotal - bytesRead).coerceAtLeast(0L)
            tvMbRemaining.text = if (remaining > 0) "${formatBytes(remaining)} remaining" else ""
        } else if (sesTotal > 0 && sesCurrent >= sesTotal) {
            // Done — reset after a moment (only once)
            pbFile.postDelayed({
                pbSync.progress = 0
                tvProgressLabel.text = "Idle"
                tvCurrentFile.text = "—"
                pbFile.progress = 0
                pbFile.visibility = View.INVISIBLE
                tvMbRemaining.text = ""
            }, 3000)
        }

        // ── Hub status ────────────────────────────────────────────────────────
        val hubIp       = ClientForegroundService.liveHubIp
        val hubName     = ClientForegroundService.liveHubName
        val tailscaleIp = ClientForegroundService.liveHubTailscaleIp
        tvHubStatus.text = when {
            hubIp != null       -> "✓  Hub on local network: ${hubName ?: hubIp}"
            tailscaleIp != null -> "✓  Hub via Tailscale: $tailscaleIp (syncing every 5 min)"
            else                -> "◎  Listening for hub…"
        }

        // ── Accessibility service status ──────────────────────────────────────
        tvAccessibilityStatus.text = if (isAccessibilityServiceEnabled())
            "✓  Background keep-alive enabled"
        else
            "✗  Enable in Settings → Accessibility → PhotoSync Client"

        // Pending deletes are handled automatically by AutoDeleteActivity (launched by the service)

        // ── Compression bar ───────────────────────────────────────────────────
        val compTotal = srv.stateCompressionTotal
        val compDone  = srv.stateCompressionDone
        val compFile  = srv.stateCompressionFile
        if (compTotal > 0) {
            pbCompression.visibility = View.VISIBLE
            pbCompression.max = compTotal
            pbCompression.progress = compDone
            tvCompressionLabel.text = "$compDone / $compTotal  ·  $compFile"
            if (compDone >= compTotal) {
                pbCompression.postDelayed({
                    pbCompression.progress = 0
                    tvCompressionLabel.text = "Idle"
                    pbCompression.visibility = View.INVISIBLE
                }, 3000)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
            else                    -> "$bytes B"
        }
    }

    // ── Log receiver ──────────────────────────────────────────────────────────

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(ClientForegroundService.EXTRA_LOG) ?: return
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "$time  $msg"
            if (logLines.size >= 100) logLines.removeFirst()
            logLines.addLast(line)
            tvLog.text = logLines.joinToString("\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_client)

        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        tvBatteryStatus    = findViewById(R.id.tv_battery_status)
        tvLog              = findViewById(R.id.tv_log)
        scrollLog          = findViewById(R.id.scroll_log)
        pbSync             = findViewById(R.id.pb_sync)
        tvProgressLabel    = findViewById(R.id.tv_progress_label)
        tvCurrentFile      = findViewById(R.id.tv_current_file)
        pbFile             = findViewById(R.id.pb_file)
        tvMbRemaining      = findViewById(R.id.tv_mb_remaining)
        btnMenu            = findViewById(R.id.btn_menu)
        pbCompression      = findViewById(R.id.pb_compression)
        tvCompressionLabel = findViewById(R.id.tv_compression_label)
        tvHubStatus           = findViewById(R.id.tv_hub_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)

        btnMenu.setOnClickListener { showDropdown(it) }
    }

    override fun onResume() {
        super.onResume()

        // Reload buffered logs
        logLines.clear()
        logLines.addAll(ClientForegroundService.getRecentLogs())
        if (logLines.isNotEmpty()) {
            tvLog.text = logLines.joinToString("\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        registerReceiver(logReceiver,
            IntentFilter(ClientForegroundService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
        pollHandler.post(pollRunnable)

        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
        pollHandler.removeCallbacks(pollRunnable)
    }

    // ── Dropdown menu ─────────────────────────────────────────────────────────

    private fun showDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_client, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_start -> {
                    startForegroundService(Intent(this, ClientForegroundService::class.java))
                    true
                }
                R.id.action_permissions -> {
                    requestMediaPermissions()
                    true
                }
                R.id.action_manage_media -> {
                    requestManageMedia()
                    true
                }
                R.id.action_write_access -> {
                    requestWriteAccess()
                    true
                }
                R.id.action_delete_originals -> {
                    deleteQueuedOriginals()
                    true
                }
                R.id.action_battery -> {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                    true
                }
                R.id.action_check_update -> {
                    checkForUpdate()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    private fun refreshStatus() {
        tvPermissionStatus.text = buildString {
            if (hasReadPermissions()) append("✓  Media read granted")
            else append("✗  Media read missing — use menu")
            append("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: MANAGE_MEDIA route
                if (hasManageMediaPermission()) append("✓  Media manage granted (compression ready)")
                else append("✗  Media manage not granted — menu → Grant Media Manage")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10–11: createWriteRequest route
                append("◎  Android 10–11: use menu → Grant Write Access before compression")
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        tvBatteryStatus.text = if (pm.isIgnoringBatteryOptimizations(packageName))
            "✓  Battery optimization exempt"
        else
            "✗  Not battery exempt — use menu"
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /** Read-only permissions — required for Phase 1 backup. */
    private fun hasReadPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)  == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** MANAGE_MEDIA — only needed for Phase 2 (delete originals after compression).
     *  Must be checked via AppOpsManager — checkSelfPermission always returns DENIED for this one. */
    private fun hasManageMediaPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val ops = getSystemService(android.app.AppOpsManager::class.java)
            val mode = ops.checkOpNoThrow(
                "android:manage_media",
                android.os.Process.myUid(),
                packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestMediaPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms, 100)
    }

    private fun requestManageMedia() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
        }
    }

    /**
     * For Android 10–11: launches a system dialog that batch-grants write access to all images.
     * Once approved, replaceFile() will succeed without MANAGE_MEDIA.
     * On Android 12+, MANAGE_MEDIA (Settings → Special app access) handles this instead.
     */
    private fun requestWriteAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Not needed on Android 9 and below", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Toast.makeText(this,
                "Use 'Grant Media Manage (Android 12+)' instead",
                Toast.LENGTH_SHORT).show()
            return
        }
        // Android 10–11: build and launch the write request
        val pending = MediaStoreHelper(this).buildWriteRequest()
        if (pending == null) {
            Toast.makeText(this, "No images found to request write access for", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startIntentSenderForResult(pending.intentSender, REQ_WRITE_ACCESS, null, 0, 0, 0)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch write request: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Launches the system delete-approval dialog for any originals that couldn't be deleted
     * automatically during compression (e.g. because MANAGE_MEDIA wasn't active at the time).
     */
    private fun deleteQueuedOriginals() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Not supported on Android 9 and below", Toast.LENGTH_SHORT).show()
            return
        }
        val helper = MediaStoreHelper(this)
        if (!helper.hasPendingDeletions()) {
            Toast.makeText(this, "No originals queued for deletion", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = helper.buildDeleteRequest()
        if (pending == null) {
            Toast.makeText(this, "Nothing to delete (queue may be stale)", Toast.LENGTH_SHORT).show()
            helper.clearPendingDeletions()
            return
        }
        try {
            startIntentSenderForResult(pending.intentSender, REQ_DELETE_ORIGINALS, null, 0, 0, 0)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch delete request: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_WRITE_ACCESS -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this,
                        "Write access granted — compression will work on next sync",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Write access denied — compression will fail", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_DELETE_ORIGINALS -> {
                if (resultCode == Activity.RESULT_OK) {
                    val helper = MediaStoreHelper(this)
                    helper.publishPendingFiles()   // make compressed copies visible
                    helper.clearPendingDeletions()
                    ClientForegroundService.liveServer?.statePendingDeletes = false
                    Toast.makeText(this, "Originals deleted — space freed!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Deletion cancelled — originals kept", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Update check ─────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val checker = UpdateChecker(this)
            checker.checkAndNotify()
            runOnUiThread {
                Toast.makeText(this,
                    "Update check complete — you'll get a notification if a new version is available",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ── Accessibility service check ───────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${com.photosync.client.service.KeepAliveAccessibilityService::class.java.name}"
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    companion object {
        private const val REQ_WRITE_ACCESS    = 1001
        private const val REQ_DELETE_ORIGINALS = 1002
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }
}
