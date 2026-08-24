package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.language.LanguageResolver
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.model.TranslationMode
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.TranslationRepository
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import javax.inject.Inject

/**
 * Handles Telegram audio messages (message.audio) without changing the
 * existing voice-message path. The Telegram mapper carries the audio file id
 * and optional MIME/name metadata; this handler resolves a Gemini-supported
 * MIME type, downloads the file, translates it, and replies to the original.
 */
class AudioMessageHandler @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val translationRepository: TranslationRepository,
    private val sendTelegramMessage: SendTelegramMessageUseCase,
    private val cacheManager: CacheManager,
    private val languageResolver: LanguageResolver,
    private val logger: BridgeLogger
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean = update.audioFileId != null

    override suspend fun handle(update: TelegramUpdate) {
        val fileId = update.audioFileId ?: return
        val mimeType = resolveGeminiMimeType(update.audioMimeType, update.audioFileName)

        if (mimeType == null) {
            logger.debug(TAG, "Ignoring unsupported Telegram audio format for update_id=${update.updateId}")
            sendTelegramMessage(update.chatId, unsupportedFormatReply(), update.messageId)
            return
        }

        val audioFile = downloadRepository.downloadAudio(fileId, mimeType).getOrElse { error ->
            logger.error(TAG, "Audio download failed for update ${update.updateId}", error)
            sendTelegramMessage(update.chatId, errorReply(), update.messageId)
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
                sourceFile = audioFile
            )

            translationRepository.translate(request)
                .onSuccess { result -> sendTelegramMessage(update.chatId, result.translatedText, update.messageId) }
                .onFailure { error ->
                    logger.error(TAG, "Audio translation failed for update ${update.updateId}", error)
                    sendTelegramMessage(update.chatId, errorReply(), update.messageId)
                }
        } finally {
            cacheManager.delete(audioFile)
        }
    }

    private fun resolveGeminiMimeType(rawMimeType: String?, fileName: String?): String? {
        val mime = rawMimeType?.trim()?.lowercase()
        return when {
            mime == "audio/mpeg" -> "audio/mp3"
            mime in SUPPORTED_MIME_TYPES -> mime
            mime == null || mime.isBlank() -> mimeFromFileName(fileName)
            else -> mimeFromFileName(fileName)
        }
    }

    private fun mimeFromFileName(fileName: String?): String? = when (
        fileName?.substringAfterLast('.', "")?.lowercase()
    ) {
        "mp3" -> "audio/mp3"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        else -> null
    }

    private fun unsupportedFormatReply(): String =
        "Sorry, this audio format is not supported yet. Please send MP3, AAC, OGG, FLAC, WAV, or AIFF."

    private fun errorReply(): String =
        "Sorry, I couldn't translate that audio file. Please try again in a moment."

    private companion object {
        const val TAG = "SigmaBridge"
        val SUPPORTED_MIME_TYPES = setOf(
            "audio/mp3",
            "audio/aac",
            "audio/ogg",
            "audio/flac",
            "audio/wav",
            "audio/aiff"
        )
    }
}
