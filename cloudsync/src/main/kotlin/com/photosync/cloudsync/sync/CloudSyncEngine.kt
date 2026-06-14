package com.photosync.cloudsync.sync

import com.photosync.cloudsync.CloudConfig
import com.photosync.cloudsync.cloud.CloudProvider
import com.photosync.cloudsync.media.VideoPoster
import com.photosync.cloudsync.media.WebpCompressor
import com.photosync.cloudsync.util.RemoteLogger
import java.io.File

/**
 * Orchestrates: for each connected cloud, enumerate every photo/video, download the original,
 * make a compressed copy (WebP for photos, JPEG poster for videos), store it locally and record a
 * manifest entry pointing back to the cloud original. Resumable — already-done files are skipped.
 */
class CloudSyncEngine(
    private val providers: List<CloudProvider>,
    private val state: SyncState,
    private val manifest: CloudManifest,
    private val outputDir: File,
    private val cacheDir: File,
) {
    @Volatile var running = false; private set
    @Volatile private var stopRequested = false
    var onProgress: ((done: Int, ok: Int, skipped: Int, current: String) -> Unit)? = null

    private val maxDownloadBytes = 200L * 1024 * 1024  // skip absurdly large files

    fun stop() { stopRequested = true }

    fun run() {
        if (running) return
        running = true; stopRequested = false
        var processed = 0; var ok = 0; var skipped = 0
        try {
            outputDir.mkdirs()
            for (provider in providers) {
                if (stopRequested) break
                if (!provider.isConfigured()) { RemoteLogger.i("${provider.label}: no credentials — skip"); continue }
                if (!provider.isAuthed()) { RemoteLogger.i("${provider.label}: not connected — skip"); continue }
                RemoteLogger.i("▶ ${provider.label}: enumerating…")
                val listedOk = provider.listAllMedia { batch ->
                    for (file in batch) {
                        if (stopRequested) return@listAllMedia
                        processed++
                        onProgress?.invoke(processed, ok, skipped, file.name)
                        if (state.isDone(file.provider, file.id)) { skipped++; continue }
                        if (file.sizeBytes !in 0..maxDownloadBytes) { skipped++; continue }

                        val isVideo = file.mimeType.startsWith("video/")
                        val bytes = provider.download(file)
                        if (bytes == null || bytes.isEmpty()) { skipped++; continue }
                        val compressed = if (isVideo) VideoPoster.makePoster(bytes, cacheDir)
                                         else WebpCompressor.compress(bytes, CloudConfig.WEBP_QUALITY)
                        if (compressed == null || compressed.isEmpty()) { skipped++; continue }

                        val ext = if (isVideo) "jpg" else "webp"
                        val safeStem = file.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                        val outName = "${file.provider}_${file.id.takeLast(12)}_$safeStem.$ext"
                        File(outputDir, outName).writeBytes(compressed)
                        manifest.add(ManifestEntry(outName, file.provider, file.id, file.name,
                            file.dateMs, file.sizeBytes, isVideo))
                        state.markDone(file.provider, file.id)
                        ok++
                        if (ok % 20 == 0) { manifest.save(); state.flush() }
                        RemoteLogger.i("✓ ${file.name} → ${compressed.size / 1024}KB ${ext.uppercase()}")
                    }
                }
                if (!listedOk) RemoteLogger.e("${provider.label}: enumeration failed (auth/network?)")
            }
        } catch (t: Throwable) {
            RemoteLogger.e("sync run error", t)
        } finally {
            manifest.save(); state.flush()
            running = false
            RemoteLogger.i("■ Done — $ok compressed, $skipped skipped, ${manifest.size()} total in library")
            onProgress?.invoke(processed, ok, skipped, "done")
        }
    }
}
