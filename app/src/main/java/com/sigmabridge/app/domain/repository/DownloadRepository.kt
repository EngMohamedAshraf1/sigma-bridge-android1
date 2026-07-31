package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.TemporaryVoiceFile

/**
 * Downloads a Telegram file by id into local temp storage. Deliberately
 * separate from TelegramRepository, which only owns the long-polling
 * lifecycle (start/stop/restart) and the raw updates stream — it has no
 * knowledge of the filesystem at all. Implemented in the data layer against
 * Telegram's getFile + file-download endpoints, using CacheManager for the
 * actual on-disk location.
 */
interface DownloadRepository {
    suspend fun downloadVoice(fileId: String): Result<TemporaryVoiceFile>
}
