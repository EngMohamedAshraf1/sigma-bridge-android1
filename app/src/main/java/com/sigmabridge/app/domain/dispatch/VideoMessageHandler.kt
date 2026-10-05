package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.data.telegram.TelegramVideoDownloadRepository
import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.language.LanguageResolver
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.media.MediaAudioExtractor
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.model.TranslationMode
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.repository.TranslationRepository
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import javax.inject.Inject

/**
 * Handles Telegram Video/VideoNote only when /translate explicitly targets
 * the media. A normal video is never claimed by this handler.
 *
 * The video itself is never sent to Gemini. Only the extracted AAC audio is
 * passed into the existing Telegram audio translation pipeline.
 */
class VideoMessageHandler @Inject constructor(
    private val downloadRepository: TelegramVideoDownloadRepository,
    private val audioExtractor: MediaAudioExtractor,
    private val translationRepository: TranslationRepository,
    private val sendTelegramMessage: SendTelegramMessageUseCase,
    private val cacheManager: CacheManager,
    private val languageResolver: LanguageResolver,
    private val logger: BridgeLogger
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean =
        update.videoTranslateRequested && update.videoFileId != null

    override suspend fun handle(update: TelegramUpdate) {
        val fileId = update.videoFileId ?: return
        val targetMessageId = update.videoMessageId ?: update.messageId

        if (update.videoFileSizeBytes != null &&
            update.videoFileSizeBytes > MAX_TELEGRAM_DOWNLOAD_BYTES
        ) {
            sendTelegramMessage(
                update.chatId,
                oversizedVideoReply(),
                targetMessageId
            )
            return
        }

        val video = downloadRepository.downloadVideo(
            fileId = fileId,
            mimeType = update.videoMimeType,
            fileName = update.videoFileName
        ).getOrElse { error ->
            logger.error(TAG, "Video download failed for update ${update.updateId}", error)
            sendTelegramMessage(update.chatId, errorReply(), targetMessageId)
            return
        }

        try {
            val audio = audioExtractor.extractAudio(video).getOrElse { error ->
                logger.error(TAG, "Video audio extraction failed for update ${update.updateId}", error)
                sendTelegramMessage(update.chatId, extractionErrorReply(), targetMessageId)
                return
            }

            try {
                val languageConfiguration = languageResolver.resolve(
                    chatId = update.chatId,
                    userId = update.senderUserId
                )

                val request = TranslationRequest(
                    mode = TranslationMode.AUDIO,
                    languagePair = languageConfiguration.toLanguagePair(),
                    sourceFile = audio
                )

                translationRepository.translate(request)
                    .onSuccess { result ->
                        sendTelegramMessage(update.chatId, result.translatedText, targetMessageId)
                    }
                    .onFailure { error ->
                        logger.error(TAG, "Video audio translation failed for update ${update.updateId}", error)
                        sendTelegramMessage(update.chatId, errorReply(), targetMessageId)
                    }
            } finally {
                cacheManager.delete(audio)
            }
        } finally {
            cacheManager.delete(video)
        }
    }

    private fun oversizedVideoReply(): String =
        "Sorry, this video is too large for the bot to download. Please send a video smaller than 20 MB."

    private fun extractionErrorReply(): String =
        "Sorry, I couldn't extract an audio track from that video."

    private fun errorReply(): String =
        "Sorry, I couldn't translate the speech in that video. Please try again in a moment."

    private companion object {
        const val TAG = "SigmaBridge"
        const val MAX_TELEGRAM_DOWNLOAD_BYTES = 20_000_000L
    }
}
