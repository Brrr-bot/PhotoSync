package com.photosync.client.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.view.ContextThemeWrapper
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.photosync.client.BuildConfig
import com.photosync.client.R
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.media.LocalImageProcessor
import com.photosync.client.media.MediaStoreHelper
import com.photosync.client.network.TailscaleIpDetector
import com.photosync.client.service.ClientForegroundService
import com.photosync.client.update.UpdateChecker
import com.photosync.shared.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Color
import com.photosync.client.ui.GlowCardLayout

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
    private lateinit var tvTailscaleStatus: TextView
    private lateinit var swMobileData: SwitchCompat
    private lateinit var tvHubFilesStatus: TextView
    private lateinit var btnHubFilesMore: TextView
    private val hubThumbViews = arrayOfNulls<ImageView>(5)

    private val logLines = ArrayDeque<String>(100)
    private val logPulseStop = Runnable {
    }
    // Track last hub IP for which we loaded thumbnails so we don't re-fetch on every poll tick
    private var thumbsLoadedForIp: String? = null

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
            findViewById<GlowCardLayout>(R.id.glow_card_upload)?.startPulse()
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
        // Stays visible once any compression has happened. Shows lifetime total +
        // current batch progress. No auto-hide (was causing visible flash on every
        // single-file batch as the timer toggled between "1/1" and "Idle").
        val compTotal     = srv.stateCompressionTotal
        val compDone      = srv.stateCompressionDone
        val compFile      = srv.stateCompressionFile
        val compLifetime  = srv.stateCompressionLifetime
        if (compTotal > 0 || compLifetime > 0) {
            if (compTotal > 0) findViewById<GlowCardLayout>(R.id.glow_card_compression)?.startPulse()
            pbCompression.visibility = View.VISIBLE
            pbCompression.max = if (compTotal > 0) compTotal else 1
            pbCompression.progress = compDone
            tvCompressionLabel.text = if (compTotal > 0)
                "$compLifetime compressed total  ·  current $compDone / $compTotal  ·  $compFile"
            else
                "$compLifetime compressed total  ·  idle"
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

    /** Builds a per-line colour-coded CharSequence for the live-log card using the shared classifier. */
    private fun colorizeLog(lines: Collection<String>): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        val it = lines.iterator()
        while (it.hasNext()) {
            val line = it.next()
            val start = sb.length
            sb.append(line)
            val color = try { android.graphics.Color.parseColor(com.photosync.shared.LogStyle.colorFor(line)) }
                        catch (_: Exception) { android.graphics.Color.parseColor(com.photosync.shared.LogStyle.GREY) }
            sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (it.hasNext()) sb.append("\n")
        }
        return sb
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(ClientForegroundService.EXTRA_LOG) ?: return
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "$time  $msg"
            if (logLines.size >= 100) logLines.removeFirst()
            logLines.addLast(line)
            tvLog.text = colorizeLog(logLines)
            val logCard = findViewById<GlowCardLayout>(R.id.glow_card_log)
            logCard?.startPulse()
            logCard?.removeCallbacks(logPulseStop)
            logCard?.postDelayed(logPulseStop, 4000L)
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_client)
        setupGlowCards()

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
        tvTailscaleStatus     = findViewById(R.id.tv_tailscale_status)
        swMobileData          = findViewById(R.id.sw_mobile_data)

        val prefs = getSharedPreferences(ClientForegroundService.PREFS_NAME, MODE_PRIVATE)
        swMobileData.isChecked = prefs.getBoolean(ClientForegroundService.KEY_ALLOW_MOBILE_DATA, false)
        swMobileData.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ClientForegroundService.KEY_ALLOW_MOBILE_DATA, checked).apply()
        }

        btnMenu.setOnClickListener { showDropdown(it) }

        // Hub files card
        tvHubFilesStatus = findViewById(R.id.tv_hub_files_status)
        btnHubFilesMore  = findViewById(R.id.btn_hub_files_more)
        for (i in 0..4) {
            val id = resources.getIdentifier("iv_hub_thumb_$i", "id", packageName)
            hubThumbViews[i] = findViewById(id)
        }
        btnHubFilesMore.setOnClickListener { openHubGallery() }
        hubThumbViews.forEachIndexed { i, iv ->
            iv?.setOnClickListener { openHubGallery() }
        }

        // Show build number in banner so we can verify OTA updates
        findViewById<TextView>(R.id.tv_banner_title).text =
            "${getString(R.string.app_name)}  v${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()

        // Reload buffered logs
        logLines.clear()
        logLines.addAll(ClientForegroundService.getRecentLogs())
        if (logLines.isNotEmpty()) {
            tvLog.text = colorizeLog(logLines)
            val logCard = findViewById<GlowCardLayout>(R.id.glow_card_log)
            logCard?.startPulse()
            logCard?.removeCallbacks(logPulseStop)
            logCard?.postDelayed(logPulseStop, 4000L)
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        registerReceiver(logReceiver,
            IntentFilter(ClientForegroundService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
        pollHandler.post(pollRunnable)
        loadHubThumbnails()

        // Request POST_NOTIFICATIONS on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }

        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
        pollHandler.removeCallbacks(pollRunnable)
        thumbsLoadedForIp = null  // reload thumbnails next time (hub may have changed)
    }

    // ── Dropdown menu ─────────────────────────────────────────────────────────

    private fun showDropdown(anchor: View) {
        val popup = PopupMenu(ContextThemeWrapper(this, R.style.AuroraPopupTheme), anchor)
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
                R.id.action_all_files -> {
                    requestAllFilesAccess()
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
                R.id.action_cleanup_duplicates -> {
                    cleanupDuplicateOriginals()
                    true
                }
                R.id.action_restore_hub -> {
                    restoreFromHubNow()
                    true
                }
                R.id.action_restore_metadata -> {
                    Toast.makeText(this, "Restoring metadata from hub — see the live log card", Toast.LENGTH_LONG).show()
                    startForegroundService(
                        Intent(this, ClientForegroundService::class.java)
                            .setAction(ClientForegroundService.ACTION_RESTORE_METADATA)
                    )
                    true
                }
                R.id.action_run_fix -> {
                    runFixSequenceNow()
                    true
                }
                R.id.action_battery -> {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
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
                R.id.action_dev_imagespace      -> { triggerDev(ClientForegroundService.ACTION_RUN_IMAGESPACE, "ImageSpace"); true }
                R.id.action_dev_videospace      -> { triggerDev(ClientForegroundService.ACTION_RUN_VIDEOSPACE, "VideoSpace"); true }
                R.id.action_dev_localfix        -> { triggerDev(ClientForegroundService.ACTION_RUN_LOCALFIX, "LocalFix"); true }
                R.id.action_dev_videodaterepair -> { triggerDev(ClientForegroundService.ACTION_RUN_VIDEODATEREPAIR, "Video date repair"); true }
                R.id.action_dev_posterrefresh   -> { triggerDev(ClientForegroundService.ACTION_RUN_POSTERREFRESH, "Poster refresh"); true }
                R.id.action_dev_fullpass        -> { triggerDev(ClientForegroundService.ACTION_RUN_FULLPASS, "Full maintenance pass"); true }
                else -> false
            }
        }
        popup.show()
    }

    /** Developer menu: fire a one-shot maintenance action at the service; results stream to the log card. */
    private fun triggerDev(action: String, label: String) {
        Toast.makeText(this, "Running $label — see the live log card", Toast.LENGTH_SHORT).show()
        startForegroundService(
            Intent(this, ClientForegroundService::class.java).setAction(action)
        )
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    private fun refreshStatus() {
        tvPermissionStatus.text = buildString {
            if (hasReadPermissions()) append("✓  Media read granted")
            else append("✗  Media read missing — use menu")
            append("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (hasManageMediaPermission()) append("✓  Media manage granted")
                else append("✗  Media manage not granted — menu → Grant Media Manage")
                append("\n")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (hasAllFilesAccess()) append("✓  All-files access granted (silent in-place compress)")
                else append("✗  All-files access missing — menu → Grant All-Files Access")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append("◎  Android 10: use menu → Grant Write Access before compression")
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        tvBatteryStatus.text = if (pm.isIgnoringBatteryOptimizations(packageName))
            "✓  Battery optimization exempt"
        else
            "✗  Not battery exempt — use menu"

        val tsIp = TailscaleIpDetector.getIp()
        tvTailscaleStatus.text = if (tsIp != null)
            "◉  http://$tsIp:${Constants.CLIENT_PORT}/"
        else
            "○  Tailscale not connected — install for remote status"
        val hasIssue = tsIp == null || ClientForegroundService.liveHubTailscaleIp == null
        findViewById<GlowCardLayout>(R.id.glow_card_status)?.setAlertMode(hasIssue)
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

    /** True if MANAGE_EXTERNAL_STORAGE has been granted (silent in-place overwrite ready). */
    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else true   // pre-Android 11: not applicable
    }

    /** Sends the user to system Settings → All-Files Access for this app. */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Not needed on Android 10 and below", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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
            REQ_CLEANUP_DUPLICATES -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this,
                        "Duplicate originals deleted — gallery cleaned up",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Cleanup cancelled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Finds all `filename (1).jpg` files that have a larger uncompressed `filename.jpg` original
     * and deletes the originals, keeping only the compressed copy.
     * On Android 12+ with MANAGE_MEDIA this runs silently. On 10-11 it shows a system dialog.
     */
    private fun cleanupDuplicateOriginals() {
        Toast.makeText(this, "Scanning for duplicate originals…", Toast.LENGTH_SHORT).show()
        Thread {
            val helper = MediaStoreHelper(this)
            val deleted = helper.cleanupOrphanedOriginals()
            runOnUiThread {
                if (deleted > 0) {
                    Toast.makeText(this,
                        "Deleted $deleted original(s) — compressed copies kept",
                        Toast.LENGTH_LONG).show()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Try batch request for any that couldn't be auto-deleted
                    val pending = helper.buildOrphanCleanupRequest()
                    if (pending != null) {
                        try {
                            startIntentSenderForResult(pending.intentSender, REQ_CLEANUP_DUPLICATES, null, 0, 0, 0)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Failed to launch cleanup: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, "No duplicate originals found", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "No duplicate originals found", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * User-initiated full restore from the hub: downloads every hub-backed file that is missing
     * from the phone and re-inserts it into MediaStore. This is the ONLY entry point for a full
     * restore — it never runs automatically (auto-restore caused an infinite re-download loop with
     * VideoSpaceManager posterisation). Posterised videos (whose poster image is still on the
     * phone) are skipped so the loop cannot re-form.
     */
    private fun restoreFromHubNow() {
        // Run the restore inside the foreground service so its progress streams to the live-log
        // card (and notification) just like sync/compression — not as transient Toasts.
        Toast.makeText(this, "Restore from hub started — see the live log card", Toast.LENGTH_LONG).show()
        startForegroundService(
            Intent(this, ClientForegroundService::class.java)
                .setAction(ClientForegroundService.ACTION_RESTORE_FROM_HUB)
        )
    }

    // ── Fix & check sequence ──────────────────────────────────────────────────

    /**
     * Manually triggers the full repair+compression sequence:
     *  1. LocalFix — date/orientation scan with hub-date fallback (clears cache for full rescan)
     *  2. ImageSpace — WebP compression for hub-confirmed images
     *  3. VideoDateRepair — MP4 atom date fix
     */
    private fun runFixSequenceNow() {
        Toast.makeText(this, "Running fix & check sequence…", Toast.LENGTH_SHORT).show()
        Thread {
            // 1. Fetch hub files for date fallback
            val ip   = effectiveHubIp()
            val port = ClientForegroundService.liveHubPort
            val hubFiles = if (ip != null) {
                try { HubFilesClient.fetchFiles(ip, port, limit = 5000) }
                catch (_: Exception) { emptyList() }
            } else emptyList()

            val hubNote = if (hubFiles.isNotEmpty()) " (${hubFiles.size} hub files)" else " (hub offline)"

            // 2. LocalFix — force full rescan
            val processor = com.photosync.client.media.LocalImageProcessor(this)
            processor.clearCheckedIds()
            val fixed = processor.processUnfixed(
                hubFiles = hubFiles,
                onProgress = { done, total, _ ->
                    if (done % 50 == 0 || done == total) {
                        runOnUiThread { Toast.makeText(this, "LocalFix $done/$total", Toast.LENGTH_SHORT).show() }
                    }
                }
            )

            // 3. ImageSpace — WebP compress hub-confirmed images
            val compSummary = try {
                com.photosync.client.media.ImageSpaceManager(this).process()
            } catch (_: Exception) { null }

            // 4. VideoSpace — transcode recent videos to H.265 720p, posterise old ones
            //    (also runs VideoDateRepair internally before the hub check)
            val vidSummary = try {
                com.photosync.client.media.VideoSpaceManager(this).process { done, total, name ->
                    if (done % 5 == 0 || done == total) {
                        runOnUiThread { Toast.makeText(this, "Video $done/$total: $name", Toast.LENGTH_SHORT).show() }
                    }
                }
            } catch (_: Exception) { null }

            runOnUiThread {
                val sb = StringBuilder()
                sb.append("Fix sequence done$hubNote\n")
                if (fixed > 0) sb.append("• $fixed date/orientation fixed\n")
                else sb.append("• Dates OK\n")
                if (compSummary != null && compSummary.compressed > 0)
                    sb.append("• ${compSummary.compressed} images → WebP (${compSummary.freedBytes / 1_048_576}MB freed)\n")
                else sb.append("• No new images to compress\n")
                if (vidSummary != null && (vidSummary.compressed > 0 || vidSummary.thumbed > 0))
                    sb.append("• ${vidSummary.compressed} videos → H.265, ${vidSummary.thumbed} posterised (${vidSummary.freedBytes / 1_048_576}MB freed)\n")
                else sb.append("• No new videos to process\n")
                Toast.makeText(this, sb.toString().trim(), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ── Hub files card ────────────────────────────────────────────────────────

    /**
     * Returns the best IP to reach the hub:
     * - Local LAN IP when the hub broadcast arrived within the last 90 s (still on same network)
     * - Tailscale IP otherwise (works across any network)
     * Falls back to whichever is non-null when the preferred one isn't available.
     */
    private fun effectiveHubIp(): String? {
        val localIp = ClientForegroundService.liveHubIp
        val tsIp    = ClientForegroundService.liveHubTailscaleIp
        val localFresh = System.currentTimeMillis() - ClientForegroundService.liveHubIpUpdatedAt < 90_000L
        return when {
            localIp != null && localFresh -> localIp
            tsIp != null                 -> tsIp
            else                         -> localIp   // stale LAN IP — best we have
        }
    }

    private fun openHubGallery() {
        val ip   = effectiveHubIp()
        val port = ClientForegroundService.liveHubPort
        if (ip == null) {
            Toast.makeText(this, "Hub not connected", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, HubGalleryActivity::class.java)
            .putExtra(HubGalleryActivity.EXTRA_HUB_IP, ip)
            .putExtra(HubGalleryActivity.EXTRA_HUB_PORT, port))
    }

    private fun loadHubThumbnails() {
        val ip   = effectiveHubIp()
        val port = ClientForegroundService.liveHubPort
        if (ip == null) {
            tvHubFilesStatus.text = "Connect to hub to see recent files"
            tvHubFilesStatus.visibility = View.VISIBLE
            return
        }
        if (ip == thumbsLoadedForIp) return   // already loaded for this hub
        tvHubFilesStatus.text = "Loading…"
        tvHubFilesStatus.visibility = View.VISIBLE
        Thread {
            val files = HubFilesClient.fetchFiles(ip, port, limit = 5)
            runOnUiThread {
                if (files.isEmpty()) {
                    tvHubFilesStatus.text = "No files on hub yet"
                    // Retry after 15s — hub cache may still be warming after a restart
                    pollHandler.postDelayed({ loadHubThumbnails() }, 15_000L)
                } else {
                    thumbsLoadedForIp = ip
                    tvHubFilesStatus.visibility = View.GONE
                }
            }
            files.take(5).forEachIndexed { i, entry ->
                val bytes = HubFilesClient.fetchThumbnail(ip, port, entry.deviceName, entry.displayName)
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                runOnUiThread { if (bmp != null) hubThumbViews[i]?.setImageBitmap(bmp) }
            }
        }.start()
    }

    // ── Update check ─────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val checker = UpdateChecker(this)
            val status = checker.checkManual()
            checker.checkTimesheetUpdate()
            runOnUiThread {
                Toast.makeText(this, status, Toast.LENGTH_LONG).show()
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
        private const val REQ_WRITE_ACCESS       = 1001
        private const val REQ_DELETE_ORIGINALS   = 1002
        private const val REQ_CLEANUP_DUPLICATES = 1003
        private const val REQ_FIX_ORIENTATION    = 1004
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }
    private fun setupGlowCards() {
        val cards = listOf(
            R.id.glow_card_status      to Color.argb(100, 0x22, 0xd3, 0xee),
            R.id.glow_card_upload      to Color.argb(100, 0xa9, 0x8b, 0xff),
            R.id.glow_card_compression to Color.argb(100, 0x2e, 0xe6, 0xa6),
            R.id.glow_card_hub         to Color.argb(100, 0x2e, 0xe6, 0xa6),
            R.id.glow_card_log         to Color.argb(100, 0xff, 0xc4, 0x4d),
        )
        cards.forEach { (id, color) ->
            findViewById<GlowCardLayout>(id)?.setGlowColor(color)
            findViewById<GlowCardLayout>(id)?.startBreathing()
        }
        // Comet pulse only on active-work cards (not status, not hub)
        listOf(R.id.glow_card_upload, R.id.glow_card_compression, R.id.glow_card_log).forEach { id ->
            findViewById<GlowCardLayout>(id)?.startPulse()
        }
    }

}
