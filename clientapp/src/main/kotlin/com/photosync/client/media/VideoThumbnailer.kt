package com.photosync.client.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Turns a video into a still JPEG "poster" with a ▶ play badge drawn on it, so a
 * placeholder image in the gallery is clearly recognisable as a (removed) video.
 */
object VideoThumbnailer {

    /** Returns JPEG bytes of a representative frame with a play badge, or null on failure. */
    fun makePosterJpeg(context: Context, videoUri: Uri, maxDim: Int = 1280): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, videoUri)
            // Grab a frame ~1s in (or the first frame for very short clips).
            val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000L
            val frameUs = if (durationUs > 2_000_000L) 1_000_000L else 0L
            val frame = mmr.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
                ?: return null

            // Scale down so posters stay small.
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

    /** Draws a translucent circle + white play triangle centred on a copy of [src]. */
    private fun drawPlayBadge(src: Bitmap): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f
        val r  = min(bmp.width, bmp.height) * 0.16f

        // Dark circle
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
        })
        // White ring
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.10f
        })
        // Play triangle
        val tri = r * 0.55f
        val path = Path().apply {
            moveTo(cx - tri * 0.5f, cy - tri)
            lineTo(cx - tri * 0.5f, cy + tri)
            lineTo(cx + tri, cy)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bmp
    }
}
