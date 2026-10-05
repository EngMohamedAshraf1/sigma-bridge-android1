package com.sigmabridge.app.data.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.AacMuxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.media.MediaAudioExtractor
import com.sigmabridge.app.domain.model.TemporaryMediaFile
import com.sigmabridge.app.domain.model.TemporaryVoiceFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts only the audio track from Telegram video/video-note media.
 *
 * Media3 removes the video track and encodes the remaining audio as AAC. The
 * output is a native AAC file so the existing Gemini audio pipeline can consume
 * it as audio/aac without introducing a video-specific Gemini path.
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3AudioExtractor @Inject constructor(
    @ApplicationContext context: Context,
    private val cacheManager: CacheManager,
    private val logger: BridgeLogger
) : MediaAudioExtractor {

    private val appContext = context.applicationContext

    override suspend fun extractAudio(media: TemporaryMediaFile): Result<TemporaryVoiceFile> {
        val destination = cacheManager.createTempVoice("audio/aac")

        return runCatching {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val transformer = Transformer.Builder(appContext)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setMuxerFactory(AacMuxerFactory())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(
                                composition: androidx.media3.transformer.Composition,
                                exportResult: ExportResult
                            ) {
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(
                                composition: androidx.media3.transformer.Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(exportException))
                                }
                            }
                        })
                        .build()

                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post {
                            transformer.cancel()
                        }
                    }

                    val input = File(media.path)
                    require(input.exists() && input.isFile) {
                        "Input media file does not exist: " + media.path
                    }

                    val mediaItem = MediaItem.fromUri(Uri.fromFile(input))
                    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                        .setRemoveVideo(true)
                        .build()

                    // Explicitly request an audio-only output sequence.
                    // Media3 1.9+ uses trackTypes on EditedMediaItemSequence
                    // to define which tracks are exported.
                    val audioOnlySequence = EditedMediaItemSequence.withAudioFrom(
                        listOf(editedMediaItem)
                    )
                    val composition = Composition.Builder(audioOnlySequence).build()

                    transformer.start(composition, destination.path)
                }
            }

            val output = File(destination.path)
            require(output.exists() && output.length() > 0L) {
                "Media audio extraction produced no output."
            }
            destination
        }.onFailure { error ->
            logger.error(
                TAG,
                "Media3 audio extraction failed for " + media.path,
                error
            )
            cacheManager.delete(destination)
        }
    }

    private companion object {
        const val TAG = "SigmaBridge"
    }

    private class AacMuxerFactory : Muxer.Factory {
        override fun create(path: String): Muxer =
            try {
                AacMuxer(FileOutputStream(path))
            } catch (error: IOException) {
                throw MuxerException("Unable to open AAC output: $path", error)
            }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            if (trackType == C.TRACK_TYPE_AUDIO) {
                ImmutableList.of(MimeTypes.AUDIO_AAC)
            } else {
                ImmutableList.of()
            }
    }
}
