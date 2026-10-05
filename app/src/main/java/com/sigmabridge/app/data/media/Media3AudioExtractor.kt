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
import androidx.media3.muxer.FileOutputStreamSeekableMuxerOutput
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Mp4Muxer.Builder
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
 * Primary path: Media3 Transformer creates an audio-only MP4 (M4A-compatible).
 * Fallback: if the source already contains AAC, Media3 Mp4Muxer remuxes only
 * the AAC track into an M4A-compatible MP4 without decoding the video.
 *
 * The output is audio/m4a, which Gemini supports directly.
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
        val destination = cacheManager.createTempVoice("audio/m4a")

        return runCatching {
            try {
                extractWithTransformer(media, destination)
            } catch (transformerError: Throwable) {
                logger.error(
                    TAG,
                    "Media3 Transformer failed for " + media.path +
                        "; attempting direct AAC-to-M4A remux.",
                    transformerError
                )

                if (!remuxAacTrackToM4a(media.path, destination.path)) {
                    val tracks = describeMediaTracks(media.path)
                    throw IllegalStateException(
                        "Could not extract usable audio from video. sourceTracks=$tracks",
                        transformerError
                    )
                }
            }

            val output = File(destination.path)
            require(output.exists() && output.isFile && output.length() > 0L) {
                "Media audio extraction produced no output."
            }
            destination
        }.onFailure { error ->
            logger.error(
                TAG,
                "Video audio extraction failed for " + media.path,
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
     * Remuxes an existing AAC track into an M4A-compatible MP4 container.
     * No audio or video decoding is performed.
     */
    private fun remuxAacTrackToM4a(
        sourcePath: String,
        destinationPath: String
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: Mp4Muxer? = null

        return try {
            extractor.setDataSource(sourcePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.lowercase() == MimeTypes.AUDIO_AAC) {
                    audioTrackIndex = index
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                logger.debug(
                    TAG,
                    "No AAC audio track found in " + sourcePath +
                        ". trackCount=" + extractor.trackCount
                )
                return false
            }

            val sourceFormat = requireNotNull(audioFormat)
            val sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate > 0) { "Invalid AAC sample rate: " + sampleRate }
            require(channelCount > 0) { "Invalid AAC channel count: " + channelCount }

            val csd = sourceFormat.getByteBuffer("csd-0")?.let { source ->
                val copy = ByteArray(source.remaining())
                source.slice().get(copy)
                copy
            }

            val media3Format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(sampleRate)
                .setChannelCount(channelCount)
                .apply {
                    if (csd != null) {
                        setInitializationData(listOf(csd))
                    }
                }
                .build()

            extractor.selectTrack(audioTrackIndex)

            FileOutputStream(destinationPath).use { outputStream ->
                val muxerOutput = FileOutputStreamSeekableMuxerOutput(outputStream)
                muxer = Builder(muxerOutput).build()

                val trackId = muxer.addTrack(media3Format)

                val maxInputSize =
                    if (sourceFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        sourceFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            .coerceAtLeast(MIN_BUFFER_SIZE)
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

                    muxer.writeSampleData(
                        trackId,
                        buffer,
                        androidx.media3.muxer.BufferInfo(
                            extractor.sampleTime,
                            sampleSize,
                            0
                        )
                    )

                    if (!extractor.advance()) break
                }
            }

            true
        } catch (error: Exception) {
            logger.error(
                TAG,
                "AAC-to-M4A remux failed for " + sourcePath,
                error
            )
            false
        } finally {
            runCatching { muxer?.close() }
            extractor.release()
        }
    }

    private fun describeMediaTracks(sourcePath: String): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourcePath)
            buildString {
                for (index in 0 until extractor.trackCount) {
                    if (index > 0) append("; ")
                    val format = extractor.getTrackFormat(index)
                    append("#").append(index)
                    append(" mime=").append(format.getString(MediaFormat.KEY_MIME))
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        append(" rate=").append(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        append(" channels=").append(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        append(" durationUs=").append(format.getLong(MediaFormat.KEY_DURATION))
                    }
                }
            }
        } catch (error: Exception) {
            "unreadable (" + error.javaClass.simpleName + ": " +
                (error.message ?: "no message") + ")"
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "SigmaBridge"
        const val MIN_BUFFER_SIZE = 64 * 1024
    }
}
