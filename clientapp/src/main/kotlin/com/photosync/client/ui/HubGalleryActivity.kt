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
import com.photosync.client.media.VideoTranscoder
import com.photosync.client.util.RemoteLogger
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

        // The tapped thumbnail for a posterized video is usually the poster ".jpg" entry (the
        // phone uploaded the poster as a new image). "Download original" must restore the real
        // VIDEO, not the poster — so if the hub also holds an .mp4/.mov with the same stem,
        // download that instead. If the tapped entry is itself a video, use it directly.
        val stem = entry.displayName.substringBeforeLast('.').lowercase()
        val videoEntry = when {
            isVideoName(entry.displayName) -> entry
            else -> entries.firstOrNull {
                isVideoName(it.displayName) &&
                it.displayName.substringBeforeLast('.').lowercase() == stem
            }
        }

        RemoteLogger.i("HubRestore: tapped '${entry.displayName}' isVideo=${isVideoName(entry.displayName)} " +
            "matchedVideo=${videoEntry?.displayName ?: "none"}")
        if (videoEntry != null) restoreVideo(ip, videoEntry)
        else                    restoreImage(ip, entry)
    }

    /** Restores a plain image (or any non-video) by buffering its bytes and saving as-is. */
    private fun restoreImage(ip: String, entry: HubFileEntry) {
        Toast.makeText(this, "Downloading ${entry.displayName}…", Toast.LENGTH_SHORT).show()
        Thread {
            val bytes = HubFilesClient.fetchFile(ip, hubPort, entry.deviceName, entry.displayName)
            if (bytes == null) {
                RemoteLogger.i("HubRestore: image fetch 404/null for '${entry.displayName}' (not on hub)")
                runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val saved = saveToGallery(entry.displayName, bytes, entry.lastModifiedMs)
            runOnUiThread {
                if (saved) Toast.makeText(this, "Saved to gallery: ${entry.displayName}", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /**
     * Restores an original video from the hub: streams it down (never buffered whole — videos
     * OOM the heap), transcodes it to H.265 the same way VideoSpaceManager does, inserts the
     * compressed result into the gallery, then deletes the poster placeholder so the restored
     * video replaces it (and won't be auto-posterized again).
     */
    private fun restoreVideo(ip: String, entry: HubFileEntry) {
        Toast.makeText(this, "Downloading ${entry.displayName}…", Toast.LENGTH_SHORT).show()
        RemoteLogger.i("HubRestore: video '${entry.displayName}' requested")
        Thread {
            val raw = File(cacheDir, "hub_dl_${entry.displayName}")
            val txc = File(cacheDir, "hub_tx_${entry.displayName.substringBeforeLast('.')}.mp4")
            try {
                val ok = HubFilesClient.fetchFileToFile(ip, hubPort, entry.deviceName, entry.displayName, raw)
                if (!ok || !raw.exists() || raw.length() == 0L) {
                    RemoteLogger.i("HubRestore: download failed for ${entry.displayName} (ok=$ok len=${raw.length()})")
                    runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                RemoteLogger.i("HubRestore: downloaded ${entry.displayName} ${raw.length() / 1_048_576}MB")

                // Restore the FULL original (no transcode here). VideoSpaceManager keeps it for a
                // 24 h grace, then transcodes it to HEVC on a later run — so the user gets the
                // original at full quality first.
                val finalFile = raw
                RemoteLogger.i("HubRestore: restoring original ${finalFile.length() / 1_048_576}MB")

                // Authoritative date: the poster placeholder already on the phone carries the
                // correct capture date — reuse it so the restored video lands on the SAME day,
                // never "today". Fall back to filename timestamp, then hub mtime.
                val posterName = entry.displayName.substringBeforeLast('.') + ".jpg"
                val dateMs = readImageDateTaken(posterName).takeIf { it > 0 }
                    ?: parseDateFromName(entry.displayName).takeIf { it > 0 }
                    ?: entry.lastModifiedMs.takeIf { it > 0 }
                    ?: System.currentTimeMillis()

                val saved = insertVideoFromFile(finalFile, entry.displayName, dateMs)
                RemoteLogger.i("HubRestore: insert saved=$saved date=$dateMs")
                if (saved) {
                    // Drop the poster placeholder and mark the video user-restored so
                    // VideoSpaceManager never re-posterizes it.
                    replacePosterWithRestoredVideo(entry.displayName)
                }
                runOnUiThread {
                    if (saved) Toast.makeText(this, "Restored video: ${entry.displayName}", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "Failed to save video", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                RemoteLogger.i("HubRestore: error on ${entry.displayName} — ${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread { Toast.makeText(this, "Restore failed", Toast.LENGTH_SHORT).show() }
            } finally {
                raw.delete(); txc.delete()
            }
        }.start()
    }

    /** Reads DATE_TAKEN (fallback DATE_ADDED) for an image already in MediaStore, or 0 if absent. */
    private fun readImageDateTaken(displayName: String): Long {
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(displayName), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val taken = c.getLong(0)
                    if (taken > 0) taken else c.getLong(1) * 1000L
                } else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * Streams [src] into the Video MediaStore under [name] with [dateMs], using the
     * IS_PENDING + double-date-update pattern so DATE_TAKEN sticks on Samsung Android 13+.
     */
    private fun insertVideoFromFile(src: File, name: String, dateMs: Long): Boolean {
        return try {
            val dateSec = dateMs / 1000L
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeFor(name))
                put(MediaStore.Video.Media.DATE_TAKEN, dateMs)
                put(MediaStore.Video.Media.DATE_ADDED, dateSec)
                put(MediaStore.Video.Media.DATE_MODIFIED, dateSec)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(collection, values) ?: return false
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: throw java.io.IOException("openOutputStream null")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.SIZE, src.length())
                        put(MediaStore.Video.Media.DATE_TAKEN, dateMs)
                        put(MediaStore.Video.Media.DATE_ADDED, dateSec)
                        put(MediaStore.Video.Media.DATE_MODIFIED, dateSec)
                    }, null, null)
                    // Samsung resets dates on IS_PENDING→0 — force them again
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.DATE_TAKEN, dateMs)
                        put(MediaStore.Video.Media.DATE_ADDED, dateSec)
                        put(MediaStore.Video.Media.DATE_MODIFIED, dateSec)
                    }, null, null)
                }
                // Patch the MP4's internal creation_time atoms so Samsung doesn't override
                // DATE_TAKEN with the transcoder's "now" — same retention step used everywhere.
                if (dateMs > 0) {
                    val patched = try { com.photosync.client.media.Mp4DateEditor.setCreationTime(this, uri, dateMs) }
                                  catch (_: Throwable) { false }
                    if (patched) {
                        runCatching {
                            contentResolver.update(uri, ContentValues().apply {
                                put(MediaStore.Video.Media.DATE_TAKEN, dateMs)
                                put(MediaStore.Video.Media.DATE_MODIFIED, dateSec)
                            }, null, null)
                        }
                        val path = contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        if (path != null) runCatching {
                            android.media.MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf("video/mp4"), null)
                        }
                    }
                    RemoteLogger.i("HubRestore: mp4 date atoms patched=$patched -> $dateMs")
                }
                true
            } catch (e: Exception) {
                // Never leave a half-written/pending row behind — it shows in the gallery as a
                // broken, today-dated placeholder. Delete the orphan and report failure.
                RemoteLogger.i("HubRestore: insert write failed (${e.javaClass.simpleName}), removing orphan row")
                runCatching { contentResolver.delete(uri, null, null) }
                false
            }
        } catch (_: Exception) { false }
    }

    private fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "mkv", "avi", "3gp", "webm")

    /** Best-effort capture date from a media filename (YYYYMMDD_HHMMSS or YYYYMMDD). */
    private fun parseDateFromName(name: String): Long {
        val stem = name.substringBeforeLast('.')
        Regex("(20\\d{2})(\\d{2})(\\d{2})[_\\-](\\d{2})(\\d{2})(\\d{2})").find(stem)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            try {
                return java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(),
                        h.toInt(), mi.toInt(), s.toInt())
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(stem)?.let { m ->
            val (y, mo, d) = m.destructured
            try {
                return java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        return 0L
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
        // Mark restored with a timestamp — VideoSpaceManager keeps the original 24 h, then transcodes
        // it to HEVC (never re-posterises). Drop any prior bare/old entry for this video first.
        val userRestored = prefs.getStringSet(com.photosync.client.media.VideoSpaceManager.KEY_USER_RESTORED, emptySet())!!
            .filterNot { it == videoName || it.startsWith("$videoName|") }.toMutableSet()
        userRestored.add("$videoName|${System.currentTimeMillis()}")

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
