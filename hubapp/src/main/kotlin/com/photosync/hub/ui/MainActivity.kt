package com.photosync.hub.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.photosync.hub.BuildConfig
import com.photosync.hub.R
import com.photosync.hub.network.TailscaleIpDetector
import com.photosync.hub.service.HubForegroundService
import com.photosync.hub.storage.SyncStateRepository
import com.photosync.hub.storage.UsbStorageManager
import com.photosync.hub.update.UpdateChecker
import com.photosync.shared.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var syncState: SyncStateRepository
    private lateinit var usbStorage: UsbStorageManager

    private lateinit var tvUsbStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvSyncStatus: TextView   // now the compression label
    private lateinit var pbCompression: ProgressBar
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var pbSync: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var pbFile: ProgressBar
    private lateinit var tvMbRemaining: TextView
    private lateinit var btnMenu: ImageButton
    private lateinit var tvTailscaleStatus: TextView

    private val logLines = ArrayDeque<String>(100)

    // ── Progress receiver ─────────────────────────────────────────────────────

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current          = intent.getIntExtra(HubForegroundService.EXTRA_CURRENT, 0)
            val total            = intent.getIntExtra(HubForegroundService.EXTRA_TOTAL, 0)
            val filename         = intent.getStringExtra(HubForegroundService.EXTRA_FILENAME) ?: ""
            val fileSizeBytes    = intent.getLongExtra(HubForegroundService.EXTRA_FILE_SIZE, 0L)
            val sessionRemaining = intent.getLongExtra(HubForegroundService.EXTRA_SESSION_REMAINING, 0L)

            if (total <= 0) return

            if (fileSizeBytes == 0L) {
                // ── Compression phase (fileSizeBytes=0 is the marker) ──────────
                pbCompression.visibility = View.VISIBLE
                pbCompression.max = total
                pbCompression.progress = current
                tvSyncStatus.text = "$current / $total  ·  $filename"
                if (current >= total) {
                    pbCompression.postDelayed({
                        pbCompression.progress = 0
                        tvSyncStatus.text = "Idle"
                        pbCompression.visibility = View.INVISIBLE
                    }, 3000)
                }
            } else {
                // ── Download phase ─────────────────────────────────────────────
                pbSync.max = total
                pbSync.progress = current
                tvProgressLabel.text = "$current / $total  ·  $filename"
                tvCurrentFile.text = filename
                tvMbRemaining.text = if (sessionRemaining > 0) "${formatBytes(sessionRemaining)} remaining" else ""
                if (current >= total) {
                    pbSync.postDelayed({
                        pbSync.progress = 0
                        tvProgressLabel.text = "Idle"
                        tvCurrentFile.text = "—"
                        tvMbRemaining.text = ""
                    }, 3000)
                }
            }
        }
    }

    private val fileBytesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytesRead  = intent.getLongExtra(HubForegroundService.EXTRA_BYTES_READ, 0L)
            val fileTotal  = intent.getLongExtra(HubForegroundService.EXTRA_FILE_TOTAL, 0L)
            val filename   = intent.getStringExtra(HubForegroundService.EXTRA_FILENAME) ?: ""
            if (fileTotal > 0) {
                pbFile.visibility = View.VISIBLE
                pbFile.max = 10000
                pbFile.progress = ((bytesRead * 10000L) / fileTotal).toInt()
                val remaining = (fileTotal - bytesRead).coerceAtLeast(0L)
                tvCurrentFile.text = if (filename.isNotEmpty()) "$filename  (${formatBytes(fileTotal)})" else tvCurrentFile.text
                tvMbRemaining.text = if (remaining > 0) "${formatBytes(remaining)} remaining" else ""
                if (bytesRead >= fileTotal) {
                    pbFile.postDelayed({
                        pbFile.progress = 0
                        pbFile.visibility = View.INVISIBLE
                    }, 800)
                }
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
            val msg = intent.getStringExtra(HubForegroundService.EXTRA_LOG) ?: return
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "$time  $msg"
            if (logLines.size >= 100) logLines.removeFirst()
            logLines.addLast(line)
            renderLog()
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_hub)

        prefs = getSharedPreferences(HubForegroundService.PREFS_NAME, MODE_PRIVATE)
        syncState = SyncStateRepository(prefs)
        usbStorage = UsbStorageManager(this, prefs)

        tvUsbStatus           = findViewById(R.id.tv_usb_status)
        tvBatteryStatus       = findViewById(R.id.tv_battery_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvSyncStatus     = findViewById(R.id.tv_sync_status)
        pbCompression    = findViewById(R.id.pb_compression)
        tvLog            = findViewById(R.id.tv_log)
        scrollLog        = findViewById(R.id.scroll_log)
        pbSync           = findViewById(R.id.pb_sync)
        tvProgressLabel  = findViewById(R.id.tv_progress_label)
        tvCurrentFile    = findViewById(R.id.tv_current_file)
        pbFile           = findViewById(R.id.pb_file)
        tvMbRemaining    = findViewById(R.id.tv_mb_remaining)
        btnMenu             = findViewById(R.id.btn_menu)
        tvTailscaleStatus   = findViewById(R.id.tv_tailscale_status)

        btnMenu.setOnClickListener { showDropdown(it) }

        // Show build number in banner so we can verify OTA updates
        findViewById<TextView>(R.id.tv_banner_title).text =
            "${getString(R.string.app_name)}  v${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()

        // Reload buffered logs
        logLines.clear()
        logLines.addAll(HubForegroundService.getRecentLogs())
        if (logLines.isNotEmpty()) {
            renderLog()
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        registerReceiver(logReceiver,
            IntentFilter(HubForegroundService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
        registerReceiver(progressReceiver,
            IntentFilter(HubForegroundService.ACTION_PROGRESS), RECEIVER_NOT_EXPORTED)
        registerReceiver(fileBytesReceiver,
            IntentFilter(HubForegroundService.ACTION_FILE_BYTES), RECEIVER_NOT_EXPORTED)

        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
        unregisterReceiver(progressReceiver)
        unregisterReceiver(fileBytesReceiver)
    }

    private fun renderLog() {
        val out = SpannableStringBuilder()
        logLines.forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            val start = out.length
            out.append(line)
            out.setSpan(
                ForegroundColorSpan(logColor(line)),
                start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvLog.text = out
    }

    private fun logColor(line: String): Int {
        val text = line.lowercase(Locale.US)
        return when {
            text.contains("error") || text.contains("failed") || text.contains("fatal") ->
                ContextCompat.getColor(this, android.R.color.holo_red_light)
            text.contains("restore") || text.contains("poster") ->
                ContextCompat.getColor(this, android.R.color.holo_purple)
            text.contains("compress") || text.contains("make space") ->
                ContextCompat.getColor(this, android.R.color.holo_orange_light)
            text.contains("sync") || text.contains("upload") || text.contains("download") ->
                ContextCompat.getColor(this, android.R.color.holo_blue_light)
            text.contains("heard") || text.contains("handshake") || text.contains("tailscale") ->
                0xFF26C6DA.toInt()
            text.contains("done") || text.contains("complete") || text.contains("saved") ||
                text.contains("confirmed") ->
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            else -> ContextCompat.getColor(this, android.R.color.white)
        }
    }

    // ── Dropdown menu ─────────────────────────────────────────────────────────

    private fun showDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_hub, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_start -> {
                    startForegroundService(Intent(this, HubForegroundService::class.java))
                    true
                }
                R.id.action_usb -> {
                    startActivity(Intent(this, UsbPickerActivity::class.java))
                    true
                }
                R.id.action_battery -> {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                    true
                }
                R.id.action_repair_orient -> {
                    repairOrientation()
                    true
                }
                R.id.action_accessibility -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    // ── Update check ─────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        android.widget.Toast.makeText(this, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val checker = UpdateChecker(this)
            checker.checkAndNotify()
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    "Update check complete — you'll get a notification if a new version is available",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ── Repair orientation ────────────────────────────────────────────────────

    private fun repairOrientation() {
        // Clear the compression cache for ALL devices so the next sync re-reads every
        // original from USB and re-compresses with the fixed orientation + timestamp code.
        syncState.clearAllCompressedFiles()
        android.widget.Toast.makeText(
            this,
            "Compression cache cleared — reconnect phone to re-compress all images with correct orientation",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    // ── Accessibility service check ───────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${com.photosync.hub.service.KeepAliveAccessibilityService::class.java.name}"
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    private fun refreshStatus() {
        tvUsbStatus.text = if (usbStorage.isReady())
            "✓  USB drive ready"
        else
            "✗  No USB drive selected — use menu"

        val pm = getSystemService(PowerManager::class.java)
        tvBatteryStatus.text = if (pm.isIgnoringBatteryOptimizations(packageName))
            "✓  Battery optimization exempt"
        else
            "✗  Not battery exempt — use menu"

        tvAccessibilityStatus.text = if (isAccessibilityServiceEnabled())
            "✓  Background keep-alive enabled"
        else
            "✗  Enable in Settings → Accessibility → PhotoSync Hub"

        val tsIp = TailscaleIpDetector.getIp()
        tvTailscaleStatus.text = if (tsIp != null)
            "◉  http://$tsIp:${Constants.HUB_HTTP_PORT}/"
        else
            "○  Tailscale not connected — install Tailscale to access remotely"

        // Append last-sync info to the USB status line
        val known = syncState.getKnownDevices()
        if (known.isNotEmpty()) {
            val syncLines = known.joinToString("\n") { key ->
                val name = syncState.getDeviceName(key) ?: key
                val ts   = syncState.getLastSync(key)
                val date = if (ts > 0)
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))
                else "never"
                "⬡  $name · $date"
            }
            tvUsbStatus.text = tvUsbStatus.text.toString() + "\n\n" + syncLines
        }
    }
}
