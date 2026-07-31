package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.model.TranslationMode
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.TranslationRepository
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import javax.inject.Inject

/**
 * The only UpdateHandler in Phase 6. Mirrors handlers.py's
 * download -> translate -> reply -> (finally) cleanup sequence exactly:
 * on a download or translation failure, the chat still gets a reply (the
 * error message) rather than silence, and the temp file is always deleted
 * whether translation succeeded or not.
 */
class VoiceMessageHandler @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val translationRepository: TranslationRepository,
    private val sendTelegramMessage: SendTelegramMessageUseCase,
    private val cacheManager: CacheManager
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean = update.voiceFileId != null

    override suspend fun handle(update: TelegramUpdate) {
        val fileId = update.voiceFileId ?: return

        val voiceFile = downloadRepository.downloadVoice(fileId).getOrElse { error ->
            sendTelegramMessage(update.chatId, errorReply(error))
            return
        }

        try {
            val request = TranslationRequest(
                mode = TranslationMode.VOICE,
                languagePair = LanguagePair.DEFAULT_MVP_PAIR,
                sourceFile = voiceFile
            )

            translationRepository.translate(request)
                .onSuccess { result -> sendTelegramMessage(update.chatId, result.translatedText) }
                .onFailure { error -> sendTelegramMessage(update.chatId, errorReply(error)) }
        } finally {
            cacheManager.delete(voiceFile)
        }
    }

    private fun errorReply(error: Throwable): String =
        "${error::class.simpleName}: ${error.message}"
}
