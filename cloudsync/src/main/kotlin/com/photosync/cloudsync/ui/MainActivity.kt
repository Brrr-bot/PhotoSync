package com.photosync.cloudsync.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.photosync.cloudsync.CloudConfig
import com.photosync.cloudsync.R
import com.photosync.cloudsync.auth.TokenStore
import com.photosync.cloudsync.cloud.CloudProvider
import com.photosync.cloudsync.cloud.GoogleDriveProvider
import com.photosync.cloudsync.cloud.OneDriveProvider
import com.photosync.cloudsync.service.CloudSyncService
import com.photosync.cloudsync.util.RemoteLogger

class MainActivity : AppCompatActivity() {

    private lateinit var tokens: TokenStore
    private val ui = Handler(Looper.getMainLooper())
    private val logBuf = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tokens = TokenStore(this)

        RemoteLogger.onMessage = { line ->
            ui.post {
                logBuf.insert(0, "$line\n")
                if (logBuf.length > 4000) logBuf.setLength(4000)
                findViewById<TextView>(R.id.log).text = logBuf.toString()
            }
        }

        findViewById<Button>(R.id.btnConnectGoogle).setOnClickListener { connect(GoogleDriveProvider(tokens)) }
        findViewById<Button>(R.id.btnConnectOneDrive).setOnClickListener { connect(OneDriveProvider(tokens)) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startSvc(CloudSyncService.ACTION_START_SYNC) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { startSvc(CloudSyncService.ACTION_STOP_SYNC) }

        startSvc(null)   // ensure the service + HTTP server are up
        refreshStatus()
        pollProgress()
    }

    private fun startSvc(action: String?) {
        val i = Intent(this, CloudSyncService::class.java)
        if (action != null) i.action = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun connect(provider: CloudProvider) {
        val codeView = findViewById<TextView>(R.id.authCode)
        if (!provider.isConfigured()) {
            codeView.text = "${provider.label}: add credentials in CloudConfig.kt first."
            return
        }
        codeView.text = "${provider.label}: requesting code…"
        Thread {
            val dc = provider.beginAuth()
            if (dc == null) { ui.post { codeView.text = "${provider.label}: failed to get a code" }; return@Thread }
            ui.post { codeView.text = "${provider.label}: open ${dc.verificationUrl} and enter code:  ${dc.userCode}" }
            val okAuth = provider.awaitAuth(dc)
            ui.post {
                codeView.text = if (okAuth) "${provider.label}: connected" else "${provider.label}: not completed (timed out?)"
                refreshStatus()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun refreshStatus() {
        findViewById<TextView>(R.id.statusGoogle).text = "Google Drive: " + when {
            !CloudConfig.googleConfigured() -> "no credentials"
            tokens.isAuthed("google") -> "connected"
            else -> "not connected"
        }
        findViewById<TextView>(R.id.statusOneDrive).text = "OneDrive: " + when {
            !CloudConfig.msConfigured() -> "no credentials"
            tokens.isAuthed("onedrive") -> "connected"
            else -> "not connected"
        }
    }

    private fun pollProgress() {
        ui.postDelayed({
            findViewById<TextView>(R.id.progress).text = CloudSyncService.lastProgress
            pollProgress()
        }, 1000)
    }

    override fun onDestroy() {
        RemoteLogger.onMessage = null
        super.onDestroy()
    }
}
