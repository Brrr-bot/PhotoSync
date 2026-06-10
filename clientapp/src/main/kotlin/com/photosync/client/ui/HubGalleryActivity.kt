package com.photosync.client.ui

import android.content.ContentValues
import android.content.Context
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
import android.widget.LinearLayout
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HubGalleryActivity : AppCompatActivity() {

    private lateinit var rvGallery: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvFileCount: TextView
    private lateinit var barSelection: LinearLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnShareSelected: ImageButton
    private lateinit var btnDeleteSelected: ImageButton

    private var hubIp: String? = null
    private var hubPort: Int = 0
    private val entries = mutableListOf<HubFileEntry>()
    private val selectedItems = mutableSetOf<HubFileEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub_gallery)

        rvGallery        = findViewById(R.id.rv_gallery)
        tvStatus         = findViewById(R.id.tv_gallery_status)
        tvFileCount      = findViewById(R.id.tv_file_count)
        barSelection     = findViewById(R.id.bar_selection)
        tvSelectionCount = findViewById(R.id.tv_selection_count)
        btnShareSelected = findViewById(R.id.btn_share_selected)
        btnDeleteSelected= findViewById(R.id.btn_delete_selected)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        btnShareSelected.setOnClickListener  { shareSelected() }

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

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun toggleSelection(entry: HubFileEntry) {
        if (selectedItems.contains(entry)) selectedItems.remove(entry)
        else selectedItems.add(entry)
        updateSelectionBar()
        val idx = entries.indexOf(entry)
        if (idx >= 0) rvGallery.adapter?.notifyItemChanged(idx)
    }

    private fun updateSelectionBar() {
        val count = selectedItems.size
        if (count == 0) {
            barSelection.visibility = View.GONE
        } else {
            barSelection.visibility = View.VISIBLE
            tvSelectionCount.text = "$count selected"
        }
    }

    // ── Delete selected ───────────────────────────────────────────────────────

    private fun confirmDeleteSelected() {
        val count = selectedItems.size
        if (count == 0) return
        val label = if (count == 1) "1 file" else "$count files"
        AlertDialog.Builder(this)
            .setTitle("Delete $label from hub?")
            .setMessage("Permanently deleted from the USB drive on the hub.")
            .setPositiveButton("Delete") { _, _ -> deleteSelected() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelected() {
        val toDelete = selectedItems.toList()
        val ip = hubIp ?: return
        Thread {
            var deleted = 0
            for (entry in toDelete) {
                val ok = HubFilesClient.deleteFile(ip, hubPort, entry.deviceName, entry.displayName)
                if (ok) {
                    deleted++
                    runOnUiThread {
                        val idx = entries.indexOf(entry)
                        if (idx >= 0) {
                            entries.removeAt(idx)
                            rvGallery.adapter?.notifyItemRemoved(idx)
                        }
                    }
                }
            }
            val d = deleted
            val total = toDelete.size
            runOnUiThread {
                selectedItems.clear()
                updateSelectionBar()
                tvFileCount.text = "${entries.size} files"
                val msg = if (d == total) "Deleted $d file${if (d > 1) "s" else ""}"
                          else "Deleted $d / $total (some failed)"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ── Share selected ────────────────────────────────────────────────────────

    private fun shareSelected() {
        val toShare = selectedItems.toList()
        val ip = hubIp ?: return
        if (toShare.isEmpty()) return
        Toast.makeText(this, "Fetching ${toShare.size} file(s)…", Toast.LENGTH_SHORT).show()
        Thread {
            val uris = ArrayList<Uri>()
            for (entry in toShare) {
                val bytes = HubFilesClient.fetchFile(ip, hubPort, entry.deviceName, entry.displayName)
                if (bytes != null) {
                    val tmpFile = File(cacheDir, entry.displayName)
                    tmpFile.writeBytes(bytes)
                    uris.add(FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile))
                }
            }
            runOnUiThread {
                if (uris.isEmpty()) {
                    Toast.makeText(this, "Failed to fetch files", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeFor(toShare.first().displayName)
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                startActivity(Intent.createChooser(intent, "Share"))
            }
        }.start()
    }

    // ── Single-file actions ───────────────────────────────────────────────────

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
            val tmpFile = File(cacheDir, entry.displayName)
            tmpFile.writeBytes(bytes)
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeFor(entry.displayName)
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
            val saved = saveToGallery(entry.displayName, bytes, entry.lastModifiedMs)
            if (saved) {
                // If this video had a poster placeholder, delete it and clear tracking so
                // VideoSpaceManager won't re-posterize the restored original.
                replacePosterWithRestoredVideo(entry.displayName)
            }
            runOnUiThread {
                if (saved) Toast.makeText(this, "Saved to gallery: ${entry.displayName}", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /**
     * If [videoName] previously had a poster placeholder (recorded by VideoSpaceManager),
     * deletes the poster from MediaStore and clears all tracking so the video won't be
     * auto-posterized again on the next VideoSpaceManager cycle.
     */
    private fun replacePosterWithRestoredVideo(videoName: String) {
        val prefs = getSharedPreferences("video_space_state", Context.MODE_PRIVATE)

        // The poster is always "<video stem>.jpg" (VideoSpaceManager.insertPoster). Delete it by
        // that computed name FIRST — unconditionally — so the placeholder is always removed even
        // if the restore-map tracking was lost (e.g. prefs cleared). The map lookup below is only
        // used to tidy the bookkeeping sets.
        val computedPoster = videoName.substringBeforeLast('.') + ".jpg"
        val restoreSet = prefs.getStringSet(com.photosync.client.media.VideoSpaceManager.KEY_RESTORE, emptySet())!!.toMutableSet()
        val mapEntry = restoreSet.find { it.endsWith("|$videoName") }
        val posterName = mapEntry?.substringBefore('|') ?: computedPoster

        // Delete the poster JPEG(s) from MediaStore — both the tracked name and the computed name.
        for (name in setOf(posterName, computedPoster)) {
            try {
                contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(name)
                )
            } catch (_: Exception) {}
        }

        // Tidy tracking sets; mark video as user-restored so it's never auto-posterized again.
        if (mapEntry != null) restoreSet.remove(mapEntry)
        val posterNames = prefs.getStringSet(com.photosync.client.media.VideoSpaceManager.KEY_POSTER_NAMES, emptySet())!!.toMutableSet()
        posterNames.remove(posterName); posterNames.remove(computedPoster)
        val userRestored = prefs.getStringSet(com.photosync.client.media.VideoSpaceManager.KEY_USER_RESTORED, emptySet())!!.toMutableSet()
        userRestored.add(videoName)

        prefs.edit()
            .putStringSet(com.photosync.client.media.VideoSpaceManager.KEY_RESTORE, restoreSet)
            .putStringSet(com.photosync.client.media.VideoSpaceManager.KEY_POSTER_NAMES, posterNames)
            .putStringSet(com.photosync.client.media.VideoSpaceManager.KEY_USER_RESTORED, userRestored)
            .apply()
    }

    private fun saveToGallery(name: String, bytes: ByteArray, dateMs: Long = 0L): Boolean {
        return try {
            val mime = mimeFor(name)
            val isVideo = mime.startsWith("video/")
            val dateSec = if (dateMs > 0) dateMs / 1000L else System.currentTimeMillis() / 1000L
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val nameCol  = if (isVideo) MediaStore.Video.Media.DISPLAY_NAME  else MediaStore.Images.Media.DISPLAY_NAME
            val mimeCol  = if (isVideo) MediaStore.Video.Media.MIME_TYPE      else MediaStore.Images.Media.MIME_TYPE
            val pathCol  = if (isVideo) MediaStore.Video.Media.RELATIVE_PATH  else MediaStore.Images.Media.RELATIVE_PATH
            val takenCol = if (isVideo) MediaStore.Video.Media.DATE_TAKEN     else MediaStore.Images.Media.DATE_TAKEN
            val addedCol = if (isVideo) MediaStore.Video.Media.DATE_ADDED     else MediaStore.Images.Media.DATE_ADDED
            val modCol   = if (isVideo) MediaStore.Video.Media.DATE_MODIFIED  else MediaStore.Images.Media.DATE_MODIFIED
            val pendCol  = if (isVideo) MediaStore.Video.Media.IS_PENDING     else MediaStore.Images.Media.IS_PENDING
            val values = ContentValues().apply {
                put(nameCol,  name)
                put(mimeCol,  mime)
                put(takenCol, dateMs)
                put(addedCol, dateSec)
                put(modCol,   dateSec)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(pathCol, if (isVideo) android.os.Environment.DIRECTORY_MOVIES + "/PhotoSync"
                                 else android.os.Environment.DIRECTORY_PICTURES + "/PhotoSync")
                    put(pendCol, 1)
                }
            }
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(pendCol,  0)
                    put(takenCol, dateMs)
                    put(addedCol, dateSec)
                    put(modCol,   dateSec)
                }, null, null)
                // Samsung resets dates on IS_PENDING→0 — force them again
                contentResolver.update(uri, ContentValues().apply {
                    put(takenCol, dateMs); put(addedCol, dateSec); put(modCol, dateSec)
                }, null, null)
            }
            true
        } catch (_: Exception) { false }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"  -> "image/png"
        "webp" -> "image/webp"
        "mp4"  -> "video/mp4"
        "mov"  -> "video/quicktime"
        else   -> "image/jpeg"
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView        = v.findViewById(R.id.iv_thumb)
            val tvName: TextView     = v.findViewById(R.id.tv_name)
            val overlay: FrameLayout = v.findViewById(R.id.overlay_downloading)
            val selOverlay: View     = v.findViewById(R.id.selection_overlay)
            val ivCheck: ImageView   = v.findViewById(R.id.iv_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_hub_photo, parent, false)
            return VH(v)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val selected = selectedItems.contains(entry)

            holder.tvName.text = entry.displayName
            holder.iv.setImageBitmap(null)
            holder.overlay.visibility = View.VISIBLE
            holder.selOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            holder.ivCheck.visibility    = if (selected) View.VISIBLE else View.GONE

            loadThumb(entry, holder.iv, holder.overlay)

            holder.itemView.setOnClickListener {
                if (selectedItems.isNotEmpty()) {
                    toggleSelection(entry)
                } else {
                    confirmDownload(entry)
                }
            }
            holder.itemView.setOnLongClickListener {
                toggleSelection(entry)
                true
            }
        }
    }

    companion object {
        const val EXTRA_HUB_IP   = "hub_ip"
        const val EXTRA_HUB_PORT = "hub_port"
    }
}
