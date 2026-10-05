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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
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
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts only the audio track from Telegram video/video-note media.
 *
 * Media3 Transformer is the primary path. It creates an audio-only MP4 using the
 * default MP4 muxer. The resulting AAC track is then written as a raw AAC file
 * with Media3 AacMuxer so the existing Gemini audio pipeline can consume it.
 *
 * If the Transformer path fails and the source already contains an AAC track,
 * the compressed AAC samples are copied directly without transcoding.
 *
 * No video frames are sent to Gemini and FFmpeg is not used.
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
        val intermediate = cacheManager.createTempMedia("video/mp4", "mp4")

        return runCatching {
            try {
                extractWithTransformer(media, intermediate)
                copyAacTrack(intermediate.path, destination.path)
            } catch (transformerError: Throwable) {
                logger.error(
                    TAG,
                    "Media3 Transformer extraction failed for " + media.path +
                        "; attempting direct AAC track extraction.",
                    transformerError
                )

                if (!extractAacTrackDirectly(media.path, destination.path)) {
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
        }.also {
            cacheManager.delete(intermediate)
        }
    }

    private suspend fun extractWithTransformer(
        media: TemporaryMediaFile,
        destination: TemporaryMediaFile
    ) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(appContext)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
     * Converts an audio-only MP4 produced by Transformer into the raw AAC file
     * consumed by the existing Gemini audio path.
     */
    private fun copyAacTrack(
        sourcePath: String,
        destinationPath: String
    ) {
        if (!extractAacTrackDirectly(sourcePath, destinationPath)) {
            error("Media3 audio-only export did not contain a readable AAC track.")
        }
    }

    /**
     * Copies an existing compressed AAC track without decoding/re-encoding.
     *
     * This is used both for the common direct MP4/AAC case and for the output of
     * the Transformer MP4 path above.
     */
    private fun extractAacTrackDirectly(
        sourcePath: String,
        destinationPath: String
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: AacMuxer? = null

        return try {
            extractor.setDataSource(sourcePath)

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
                logger.debug(
                    TAG,
                    "No AAC audio track found in " + sourcePath + ". Track count=" +
                        extractor.trackCount
                )
                return false
            }

            val format = requireNotNull(audioFormat)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            require(sampleRate in SUPPORTED_SAMPLE_RATES) {
                "AAC track has unsupported sample rate: " + sampleRate
            }
            require(channelCount in 1..7) {
                "AAC track has unsupported channel count: " + channelCount
            }

            val csd = format.getByteBuffer("csd-0")?.let { source ->
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

            muxer = AacMuxer(FileOutputStream(destinationPath))
            muxer.addTrack(media3Format)

            val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(MIN_BUFFER_SIZE)
            } else {
                MIN_BUFFER_SIZE
            }

            val buffer = java.nio.ByteBuffer.allocateDirect(maxInputSize)

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
                "Direct AAC track extraction failed for " + sourcePath,
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
        const val MIN_BUFFER_SIZE = 64 * 1024
        val SUPPORTED_SAMPLE_RATES = setOf(
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
            22_050, 16_000, 12_000, 11_025, 8_000, 7_350
        )
    }
}
