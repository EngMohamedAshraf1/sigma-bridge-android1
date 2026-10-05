package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.TemporaryMediaFile
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Video-specific Telegram download path.
 *
 * It is intentionally separate from TelegramDownloadRepository so the
 * established Voice/Audio download paths remain unchanged.
 */
@Singleton
class TelegramVideoDownloadRepository @Inject constructor(
    private val fileApiClient: TelegramFileApiClient,
    private val settingsRepository: SettingsRepository,
    private val cacheManager: CacheManager
) {
    suspend fun downloadVideo(
        fileId: String,
        mimeType: String?,
        fileName: String?
    ): Result<TemporaryMediaFile> = runCatching {
        val token = settingsRepository.botToken.first()
            ?: error("Cannot download: bot token not set")

        val telegramFilePath = fileApiClient.getFilePath(token, fileId)
        val normalizedMime =
            mimeType?.trim()?.lowercase().takeUnless { it.isNullOrBlank() } ?: "video/mp4"
        val extension = extensionFor(fileName, normalizedMime)
        val destination = cacheManager.createTempMedia(normalizedMime, extension)

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

    private fun extensionFor(fileName: String?, mimeType: String): String {
        val nameExtension = fileName?.substringAfterLast('.', "")?.lowercase()
        if (!nameExtension.isNullOrBlank() &&
            nameExtension.length <= 8 &&
            nameExtension.all { it.isLetterOrDigit() }
        ) {
            return nameExtension
        }

        return when (mimeType) {
            "video/mp4" -> "mp4"
            "video/mpeg" -> "mpeg"
            "video/mov" -> "mov"
            "video/avi" -> "avi"
            "video/x-flv" -> "flv"
            "video/mpg" -> "mpg"
            "video/webm" -> "webm"
            "video/wmv" -> "wmv"
            "video/3gpp" -> "3gp"
            else -> "mp4"
        }
    }
}
