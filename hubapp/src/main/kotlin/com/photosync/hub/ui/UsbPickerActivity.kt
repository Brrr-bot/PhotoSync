package com.photosync.hub.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.photosync.hub.service.HubForegroundService
import com.photosync.hub.storage.UsbStorageManager

/**
 * Transparent trampoline activity used to launch the SAF document-tree picker.
 * The result is handled here so UsbStorageManager (which lives in a Service)
 * doesn't need Activity context for the picker itself.
 */
class UsbPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val prefs = getSharedPreferences(HubForegroundService.PREFS_NAME, MODE_PRIVATE)
                UsbStorageManager(this, prefs).persistUri(uri)
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 42
    }
}
