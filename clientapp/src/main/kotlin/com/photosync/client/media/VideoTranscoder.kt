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
 * Transcodes a video to H.265 HEVC at 1080p/6 Mbps using Media3 Transformer.
 * 1080p keeps enough spatial resolution that the codec can spread its bits across more
 * pixels, eliminating the "oily" / waxy artefact that 720p/4 Mbps showed on Samsung
 * high-detail footage.  6 Mbps H.265 still cuts file size by 60–80 % vs the original.
 */
object VideoTranscoder {

    @OptIn(UnstableApi::class)
    fun transcode(context: Context, inputUri: Uri, outputPath: String,
                  targetHeight: Int = 1080, bitrate: Int = 6_000_000): Boolean {
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