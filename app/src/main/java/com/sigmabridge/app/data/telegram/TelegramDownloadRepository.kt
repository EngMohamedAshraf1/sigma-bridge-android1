package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.TemporaryVoiceFile
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Telegram file lookup/download and CacheManager allocation.
 * Voice behavior remains unchanged; Audio supplies its MIME type so the
 * temporary file and later Gemini upload preserve the actual format.
 */
@Singleton
class TelegramDownloadRepository @Inject constructor(
    private val fileApiClient: TelegramFileApiClient,
    private val settingsRepository: SettingsRepository,
    private val cacheManager: CacheManager
) : DownloadRepository {

    override suspend fun downloadVoice(fileId: String): Result<TemporaryVoiceFile> =
        download(fileId, "audio/ogg")

    override suspend fun downloadAudio(
        fileId: String,
        mimeType: String
    ): Result<TemporaryVoiceFile> = download(fileId, mimeType)

    private suspend fun download(
        fileId: String,
        mimeType: String
    ): Result<TemporaryVoiceFile> = runCatching {
        val token = settingsRepository.botToken.first()
            ?: error("Cannot download: bot token not set")

        val telegramFilePath = fileApiClient.getFilePath(token, fileId)
        val destination = cacheManager.createTempVoice(mimeType)

        try {
            fileApiClient.downloadFile(
                botToken = token,
                filePath = telegramFilePath,
                destinationPath = destination.path
            )
        } catch (error: Exception) {
            cacheManager.delete(destination)
            throw error
        }

        destination
    }
}
