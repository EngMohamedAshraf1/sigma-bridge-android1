package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.data.telegram.TelegramImageDownloadRepository
import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.language.LanguageResolver
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.repository.ImageTranslationRepository
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import javax.inject.Inject

/** Handles Telegram photos only when explicitly requested with @sigma_bridge_bot. */
class ImageMessageHandler @Inject constructor(
    private val downloadRepository: TelegramImageDownloadRepository,
    private val imageTranslationRepository: ImageTranslationRepository,
    private val sendTelegramMessage: SendTelegramMessageUseCase,
    private val cacheManager: CacheManager,
    private val languageResolver: LanguageResolver,
    private val logger: BridgeLogger
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean =
        update.photoTranslateRequested && update.photoFileId != null

    override suspend fun handle(update: TelegramUpdate) {
        val fileId = update.photoFileId ?: return
        val targetMessageId = update.photoMessageId ?: update.messageId

        if (update.photoFileSizeBytes != null &&
            update.photoFileSizeBytes > MAX_TELEGRAM_DOWNLOAD_BYTES
        ) {
            sendTelegramMessage(
                update.chatId,
                oversizedImageReply(),
                targetMessageId
            )
            return
        }

        val image = downloadRepository.downloadImage(fileId).getOrElse { error ->
            logger.error(
                TAG,
                "Image download failed for update ${update.updateId}",
                error
            )
            sendTelegramMessage(update.chatId, errorReply(), targetMessageId)
            return
        }

        try {
            val languageConfiguration = languageResolver.resolve(
                chatId = update.chatId,
                userId = update.senderUserId
            )

            imageTranslationRepository.translate(
                image = image,
                languagePair = languageConfiguration.toLanguagePair()
            ).onSuccess { translated ->
                sendTelegramMessage(update.chatId, translated, targetMessageId)
            }.onFailure { error ->
                logger.error(
                    TAG,
                    "Image translation failed for update ${update.updateId}",
                    error
                )
                sendTelegramMessage(update.chatId, errorReply(), targetMessageId)
            }
        } finally {
            cacheManager.delete(image)
        }
    }

    private fun oversizedImageReply(): String =
        "Sorry, this image is too large. Please send an image smaller than 15 MB."

    private fun errorReply(): String =
        "Sorry, I couldn't translate the text in that image. Please try again in a moment."

    private companion object {
        const val TAG = "SigmaBridge"
        const val MAX_TELEGRAM_DOWNLOAD_BYTES = 20_000_000L
    }
}
