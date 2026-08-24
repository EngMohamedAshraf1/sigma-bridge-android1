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
 * existing voice-message path. The handler validates the Telegram download
 * ceiling before downloading, normalizes common MIME aliases, downloads the
 * file, translates it, and replies to the original message.
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
        val declaredSize = update.audioFileSizeBytes

        if (declaredSize != null && declaredSize > MAX_TELEGRAM_DOWNLOAD_BYTES) {
            logger.debug(
                TAG,
                "Rejecting oversized Telegram audio for update_id=${update.updateId}; size=$declaredSize"
            )
            sendTelegramMessage(update.chatId, oversizedFileReply(), update.messageId)
            return
        }

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
        return when (mime) {
            "audio/mpeg", "audio/x-mpeg", "audio/mpga" -> "audio/mp3"
            "audio/x-wav", "audio/wave" -> "audio/wav"
            "audio/x-flac" -> "audio/flac"
            "audio/x-aiff", "audio/x-aifc" -> "audio/aiff"
            in SUPPORTED_MIME_TYPES -> mime
            null, "" -> mimeFromFileName(fileName)
            else -> mimeFromFileName(fileName)
        }
    }

    private fun mimeFromFileName(fileName: String?): String? = when (
        fileName?.substringAfterLast('.', "")?.lowercase()
    ) {
        "mp3", "mpga" -> "audio/mp3"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aiff", "aif", "aifc" -> "audio/aiff"
        else -> null
    }

    private fun oversizedFileReply(): String =
        "Sorry, this audio file is too large for the bot to download. Please send an audio file smaller than 20 MB."

    private fun unsupportedFormatReply(): String =
        "Sorry, this audio format is not supported yet. Please send MP3, AAC, OGG, FLAC, WAV, or AIFF."

    private fun errorReply(): String =
        "Sorry, I couldn't translate that audio file. Please try again in a moment."

    private companion object {
        const val TAG = "SigmaBridge"
        const val MAX_TELEGRAM_DOWNLOAD_BYTES = 20_000_000L

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
