package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.TemporaryImageFile
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Separate Telegram image downloader; existing voice/audio/video downloaders are untouched. */
@Singleton
class TelegramImageDownloadRepository @Inject constructor(
    private val fileApiClient: TelegramFileApiClient,
    private val settingsRepository: SettingsRepository,
    private val cacheManager: CacheManager
) {
    suspend fun downloadImage(fileId: String): Result<TemporaryImageFile> = runCatching {
        val token = settingsRepository.botToken.first()
            ?: error("Cannot download image: bot token not set")

        val telegramFilePath = fileApiClient.getFilePath(token, fileId)
        val image = cacheManager.createTempImage("image/jpeg")

        try {
            fileApiClient.downloadFile(
                botToken = token,
                filePath = telegramFilePath,
                destinationPath = image.path
            )
        } catch (error: Exception) {
            cacheManager.delete(image)
            throw error
        }

        image
    }
}
