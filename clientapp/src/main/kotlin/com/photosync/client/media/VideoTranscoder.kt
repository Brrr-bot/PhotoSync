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
 * Transcodes a video to a smaller, lower-quality MP4 using Media3 Transformer.
 * Downscales to ~480p and caps the bitrate so recent clips stay watchable but small.
 */
object VideoTranscoder {

    /**
     * Transcodes [inputUri] into [outputPath] (an app-private temp file path).
     * Blocks the calling (background) thread until done. Returns true on success.
     */
    @OptIn(UnstableApi::class)
    fun transcode(context: Context, inputUri: Uri, outputPath: String,
                  targetHeight: Int = 480, bitrate: Int = 1_500_000): Boolean {
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
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
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
                    .setEffects(Effects(/* audioProcessors = */ emptyList(), /* videoEffects = */ effects))
                    .build()

                transformer.start(edited, outputPath)
            } catch (_: Throwable) {
                ok.set(false); latch.countDown()
            }
        }

        // Cap how long any single video may take.
        if (!latch.await(15, TimeUnit.MINUTES)) return false
        return ok.get()
    }
}
