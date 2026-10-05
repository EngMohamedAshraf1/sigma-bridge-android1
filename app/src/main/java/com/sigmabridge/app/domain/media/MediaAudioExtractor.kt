package com.sigmabridge.app.domain.media

import com.sigmabridge.app.domain.model.TemporaryMediaFile
import com.sigmabridge.app.domain.model.TemporaryVoiceFile

/**
 * Extracts only the audio track from a media file.
 */
interface MediaAudioExtractor {
    suspend fun extractAudio(media: TemporaryMediaFile): Result<TemporaryVoiceFile>
}
