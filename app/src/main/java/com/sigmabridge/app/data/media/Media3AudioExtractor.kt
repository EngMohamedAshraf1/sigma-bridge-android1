package com.sigmabridge.app.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
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
            try {
                extractWithTransformer(media, destination)
            } catch (transformerError: Throwable) {
                logger.error(
                    TAG,
                    "Media3 Transformer extraction failed for " + media.path +
                        "; attempting direct AAC track extraction if the source audio is AAC.",
                    transformerError
                )

                if (!extractAacTrackDirectly(media, destination)) {
                    throw transformerError
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

    private suspend fun extractWithTransformer(
        media: TemporaryMediaFile,
        destination: TemporaryVoiceFile
    ) {
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

                val audioOnlySequence = EditedMediaItemSequence.withAudioFrom(
                    listOf(editedMediaItem)
                )
                val composition = Composition.Builder(audioOnlySequence).build()

                transformer.start(composition, destination.path)
            }
        }
    }

    /**
     * Fallback for the common MP4/AAC case.
     *
     * Media3 Transformer remains the primary path. If Transformer cannot export the
     * audio-only composition on the device/input, read the existing compressed AAC
     * samples directly and pass them through Media3's AacMuxer. This does not decode
     * video, does not invoke FFmpeg, and still produces the same audio/aac output
     * consumed by the existing Gemini pipeline.
     *
     * @return true when an AAC audio track was found and successfully written.
     */
    private fun extractAacTrackDirectly(
        media: TemporaryMediaFile,
        destination: TemporaryVoiceFile
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: AacMuxer? = null

        return try {
            extractor.setDataSource(media.path)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase()
                if (mime == MimeTypes.AUDIO_AAC) {
                    audioTrackIndex = index
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                logger.debug(TAG, "No AAC audio track found in " + media.path)
                return false
            }

            val format = requireNotNull(audioFormat)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate > 0) { "AAC track has invalid sample rate: " + sampleRate }
            require(channelCount in 1..7) {
                "AAC track has unsupported channel count: " + channelCount
            }

            val csd = format.getByteBuffer(MediaFormat.KEY_CSD_0)?.let { source ->
                val copy = ByteArray(source.remaining())
                source.slice().get(copy)
                copy
            } ?: error("AAC track has no codec configuration data.")

            val media3Format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(sampleRate)
                .setChannelCount(channelCount)
                .setInitializationData(listOf(csd))
                .build()

            extractor.selectTrack(audioTrackIndex)

            muxer = AacMuxer(FileOutputStream(destination.path))
            muxer.addTrack(media3Format)

            val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                64 * 1024
            }

            val buffer = java.nio.ByteBuffer.allocateDirect(maxOf(maxInputSize, 64 * 1024))

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                buffer.position(0)
                buffer.limit(sampleSize)

                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    C.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }

                muxer.writeSampleData(
                    0,
                    buffer,
                    androidx.media3.muxer.BufferInfo(
                        extractor.sampleTime,
                        sampleSize,
                        flags
                    )
                )

                if (!extractor.advance()) break
            }

            true
        } catch (error: Exception) {
            logger.error(
                TAG,
                "Direct AAC track extraction failed for " + media.path,
                error
            )
            false
        } finally {
            runCatching { muxer?.close() }
            extractor.release()
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
