package com.photosync.client.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Turns a video into a still JPEG poster with a play badge drawn on it, so a
 * placeholder image in the gallery is clearly recognisable as a (removed) video.
 */
object VideoThumbnailer {

    /** Marker stored in EXIF Software tag so the upload scanner can skip poster images. */
    internal const val POSTER_MARKER = "PhotoSync video poster"

    /** Returns JPEG bytes of a representative frame with a play badge, or null on failure. */
    fun makePosterJpeg(context: Context, videoUri: Uri, maxDim: Int = 1280): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, videoUri)
            val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000L
            val frameUs = if (durationUs > 2_000_000L) 1_000_000L else 0L
            val frame = mmr.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
                ?: return null

            val scaled = run {
                val w = frame.width; val h = frame.height
                if (w <= 0 || h <= 0) frame
                else {
                    val factor = min(1.0, maxDim.toDouble() / maxOf(w, h))
                    if (factor >= 1.0) frame
                    else Bitmap.createScaledBitmap(frame, (w * factor).toInt(), (h * factor).toInt(), true)
                }
            }

            val out = drawPlayBadge(scaled)
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            if (out !== frame) out.recycle()
            if (scaled !== frame && scaled !== out) scaled.recycle()
            frame.recycle()
            baos.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Stamps EXIF DateTimeOriginal + a [POSTER_MARKER] Software tag into JPEG [jpeg].
     * Returns modified bytes (returns original bytes unchanged on any failure).
     * - Gallery uses DateTimeOriginal to sort the poster at the correct date.
     * - Upload scanner checks Software tag to skip poster images (they are not originals).
     */
    fun stampPosterExif(jpeg: ByteArray, takenMs: Long): ByteArray {
        if (jpeg.isEmpty()) return jpeg
        val tmp = java.io.File.createTempFile("vsp_", ".jpg")
        return try {
            tmp.writeBytes(jpeg)
            val exif = ExifInterface(tmp.absolutePath)
            val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(takenMs))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,  dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME,            dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED,  dateStr)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE,            POSTER_MARKER)
            exif.saveAttributes()
            tmp.readBytes()
        } catch (_: Exception) { jpeg }
        finally { tmp.delete() }
    }

    /**
     * Draws a small, translucent play badge centred on a copy of [src] so the photo behind it
     * stays visible. The badge is deliberately subtle: a faint dark disc, a thin translucent
     * white ring, and a translucent white triangle nudged slightly right so it reads as centred
     * inside the disc (a play triangle whose centroid sits exactly at centre looks left-heavy).
     */
    private fun drawPlayBadge(src: Bitmap): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f
        val r  = min(bmp.width, bmp.height) * 0.10f   // smaller (was 0.16)

        // Faint dark disc — low alpha so the underlying image shows through.
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 0, 0, 0)
        })
        // Thin, translucent white ring.
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(130, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = r * 0.07f
        })
        // Translucent white play triangle, optically centred (nudged right by dx).
        val tri = r * 0.5f
        val dx  = r * 0.10f
        val path = Path().apply {
            moveTo(cx - tri * 0.45f + dx, cy - tri)
            lineTo(cx - tri * 0.45f + dx, cy + tri)
            lineTo(cx + tri * 0.9f  + dx, cy)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 255, 255) })
        return bmp
    }
}
