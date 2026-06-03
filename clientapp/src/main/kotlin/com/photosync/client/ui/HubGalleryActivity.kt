package com.photosync.client.ui

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photosync.client.R
import com.photosync.client.hub.HubFileEntry
import com.photosync.client.hub.HubFilesClient
import com.photosync.client.service.ClientForegroundService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HubGalleryActivity : AppCompatActivity() {

    private lateinit var rvGallery: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvFileCount: TextView

    private var hubIp: String? = null
    private var hubPort: Int = 0
    private val entries = mutableListOf<HubFileEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub_gallery)

        rvGallery   = findViewById(R.id.rv_gallery)
        tvStatus    = findViewById(R.id.tv_gallery_status)
        tvFileCount = findViewById(R.id.tv_file_count)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        hubIp   = intent.getStringExtra(EXTRA_HUB_IP)
        hubPort = intent.getIntExtra(EXTRA_HUB_PORT, 8767)

        rvGallery.layoutManager = GridLayoutManager(this, 3)
        rvGallery.adapter = GalleryAdapter()

        loadFiles()
    }

    private fun loadFiles() {
        val ip = hubIp ?: run {
            tvStatus.text = "Hub not connected"
            return
        }
        tvStatus.text = "Loading…"
        tvStatus.visibility = View.VISIBLE
        Thread {
            val files = HubFilesClient.fetchFiles(ip, hubPort, limit = 10_000)
            runOnUiThread {
                if (files.isEmpty()) {
                    tvStatus.text = "No files found on hub"
                } else {
                    tvStatus.visibility = View.GONE
                    tvFileCount.text = "${files.size} files"
                    entries.clear()
                    entries.addAll(files)
                    rvGallery.adapter?.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun loadThumb(entry: HubFileEntry, iv: ImageView, overlay: FrameLayout) {
        val key = "${entry.deviceName}/${entry.displayName}"
        // Use ThumbnailCache (disk + memory) so thumbnails survive screen rotations and reopens.
        ThumbnailCache.get(this, key)?.let { iv.setImageBitmap(it); overlay.visibility = View.GONE; return }
        val ip = hubIp ?: return
        Thread {
            val bytes = HubFilesClient.fetchThumbnail(ip, hubPort, entry.deviceName, entry.displayName)
            val bmp = bytes?.let { ThumbnailCache.put(this, key, it) }
            runOnUiThread {
                if (bmp != null) iv.setImageBitmap(bmp)
                overlay.visibility = View.GONE
            }
        }.start()
    }

    private fun confirmDownload(entry: HubFileEntry) {
        val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val dateStr = if (entry.lastModifiedMs > 0) dateFmt.format(Date(entry.lastModifiedMs)) else "unknown date"
        val sizeMb  = "%.1f MB".format(entry.sizeBytes / 1_048_576.0)

        AlertDialog.Builder(this)
            .setTitle(entry.displayName)
            .setMessage("Device: ${entry.deviceName}\nDate: $dateStr\nSize: $sizeMb")
            .setPositiveButton("Download") { _, _ -> downloadFile(entry) }
            .setNeutralButton("Share via Hub") { _, _ -> shareFromHub(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFromHub(entry: HubFileEntry) {
        val ip = hubIp ?: return
        Toast.makeText(this, "Fetching original…", Toast.LENGTH_SHORT).show()
        Thread {
            val bytes = HubFilesClient.fetchFile(ip, hubPort, entry.deviceName, entry.displayName)
            if (bytes == null) {
                runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val mime = when (entry.displayName.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png"  -> "image/png"
                "webp" -> "image/webp"
                "mp4"  -> "video/mp4"
                "mov"  -> "video/quicktime"
                else   -> "image/jpeg"
            }
            val tmpFile = File(cacheDir, entry.displayName)
            tmpFile.writeBytes(bytes)
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share ${entry.displayName}"))
            }
        }.start()
    }

    private fun downloadFile(entry: HubFileEntry) {
        val ip = hubIp ?: return
        Toast.makeText(this, "Downloading ${entry.displayName}…", Toast.LENGTH_SHORT).show()
        Thread {
            val bytes = HubFilesClient.fetchFile(ip, hubPort, entry.deviceName, entry.displayName)
            if (bytes == null) {
                runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val saved = saveToGallery(entry.displayName, bytes)
            runOnUiThread {
                if (saved) Toast.makeText(this, "Saved to gallery: ${entry.displayName}", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun saveToGallery(name: String, bytes: ByteArray): Boolean {
        return try {
            val mime = when (name.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png"  -> "image/png"
                "webp" -> "image/webp"
                "mp4"  -> "video/mp4"
                "mov"  -> "video/quicktime"
                else   -> "image/jpeg"
            }
            val isVideo = mime.startsWith("video/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) Environment.DIRECTORY_MOVIES + "/PhotoSync"
                        else Environment.DIRECTORY_PICTURES + "/PhotoSync")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            true
        } catch (_: Exception) { false }
    }

    inner class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.iv_thumb)
            val tvName: TextView = v.findViewById(R.id.tv_name)
            val overlay: FrameLayout = v.findViewById(R.id.overlay_downloading)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_hub_photo, parent, false)
            return VH(v)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.tvName.text = entry.displayName
            holder.iv.setImageBitmap(null)
            holder.overlay.visibility = View.VISIBLE
            loadThumb(entry, holder.iv, holder.overlay)
            holder.itemView.setOnClickListener { confirmDownload(entry) }
            holder.itemView.setOnLongClickListener {
                confirmDeleteFromHub(entry, holder.adapterPosition)
                true
            }
        }
    }

    private fun confirmDeleteFromHub(entry: HubFileEntry, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete from hub?")
            .setMessage("\"${entry.displayName}\" will be permanently deleted from the USB drive on the hub.\n\nDevice: ${entry.deviceName}")
            .setPositiveButton("Delete") { _, _ -> deleteFromHub(entry, position) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFromHub(entry: HubFileEntry, position: Int) {
        val ip = hubIp ?: return
        Toast.makeText(this, "Deleting…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = HubFilesClient.deleteFile(ip, hubPort, entry.deviceName, entry.displayName)
            runOnUiThread {
                if (ok) {
                    val idx = entries.indexOf(entry)
                    if (idx >= 0) {
                        entries.removeAt(idx)
                        rvGallery.adapter?.notifyItemRemoved(idx)
                        tvFileCount.text = "${entries.size} files"
                    }
                    Toast.makeText(this, "Deleted: ${entry.displayName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Delete failed — hub not reachable or file not found", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_HUB_IP   = "hub_ip"
        const val EXTRA_HUB_PORT = "hub_port"
    }
}
