package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.TemporaryVoiceFile
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Never touches java.io.File or Context.cacheDir itself — it asks
 * CacheManager for a destination (UUID-named) and asks TelegramFileApiClient
 * to write bytes there. This class only orchestrates the two.
 */
@Singleton
class TelegramDownloadRepository @Inject constructor(
    private val fileApiClient: TelegramFileApiClient,
    private val settingsRepository: SettingsRepository,
    private val cacheManager: CacheManager
) : DownloadRepository {

    override suspend fun downloadVoice(fileId: String): Result<TemporaryVoiceFile> = runCatching {
        val token = settingsRepository.botToken.first()
            ?: error("Cannot download: bot token not set")

        val telegramFilePath = fileApiClient.getFilePath(token, fileId)
        val destination = cacheManager.createTempVoice()

        fileApiClient.downloadFile(
            botToken = token,
            filePath = telegramFilePath,
            destinationPath = destination.path
        )

        destination
    }
}
