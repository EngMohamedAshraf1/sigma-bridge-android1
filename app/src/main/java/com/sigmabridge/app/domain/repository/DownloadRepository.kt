package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.TemporaryVoiceFile

/**
 * Downloads Telegram media by file id into local temp storage. The existing
 * voice method is preserved; Audio adds a MIME-aware path without changing
 * Telegram polling responsibilities.
 */
interface DownloadRepository {
    suspend fun downloadVoice(fileId: String): Result<TemporaryVoiceFile>

    suspend fun downloadAudio(
        fileId: String,
        mimeType: String
    ): Result<TemporaryVoiceFile>
}
