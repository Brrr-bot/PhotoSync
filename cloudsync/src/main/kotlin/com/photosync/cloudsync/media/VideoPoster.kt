package com.photosync.cloudsync.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.photosync.cloudsync.util.RemoteLogger
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Makes a small JPEG poster from a video's first sync frame. The full video stays in the cloud and
 * is recalled on demand; the poster is the gallery placeholder (same idea as PhotoSync video posters).
 */
object VideoPoster {
    fun makePoster(videoBytes: ByteArray, cacheDir: File, quality: Int = 80): ByteArray? {
        var tmp: File? = null
        val mmr = MediaMetadataRetriever()
        return try {
            cacheDir.mkdirs()
            tmp = File.createTempFile("cloudvid_", ".mp4", cacheDir)
            tmp.writeBytes(videoBytes)
            mmr.setDataSource(tmp.absolutePath)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val out = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, quality, out)
            frame.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            RemoteLogger.e("video poster failed", t); null
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
            tmp?.delete()
        }
    }
}
