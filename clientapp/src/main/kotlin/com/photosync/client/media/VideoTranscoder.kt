package com.photosync.client.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transcodes a video to H.265 HEVC at 720p/4 Mbps using Media3 Transformer.
 * H.265 delivers significantly better quality-per-bit than H.264 at the same bitrate,
 * so 720p/4 Mbps HEVC looks noticeably better than the old 480p/1.5 Mbps H.264.
 */
object VideoTranscoder {

    @OptIn(UnstableApi::class)
    fun transcode(context: Context, inputUri: Uri, outputPath: String,
                  targetHeight: Int = 720, bitrate: Int = 4_000_000): Boolean {
        val latch = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)

        Handler(Looper.getMainLooper()).post {
            try {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrate).build()
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            ok.set(true); latch.countDown()
                        }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            ok.set(false); latch.countDown()
                        }
                    })
                    .build()

                val effects: List<Effect> = listOf(Presentation.createForHeight(targetHeight))
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                    .setEffects(Effects(emptyList(), effects))
                    .build()

                transformer.start(edited, outputPath)
            } catch (_: Throwable) {
                ok.set(false); latch.countDown()
            }
        }

        if (!latch.await(15, TimeUnit.MINUTES)) return false
        return ok.get()
    }
}