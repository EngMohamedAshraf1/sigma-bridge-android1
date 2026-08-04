package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.model.TranslationMode
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.TranslationRepository
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import javax.inject.Inject

/**
 * The only UpdateHandler in Phase 6, extended in Phase 9.0 for group chats.
 * Mirrors handlers.py's download -> translate -> reply -> (finally) cleanup
 * sequence exactly: on a download or translation failure, the chat still
 * gets a reply (the error message) rather than silence, and the temp file
 * is always deleted whether translation succeeded or not.
 *
 * update.chatId already identifies the exact chat an update came from,
 * private or group/supergroup alike — Telegram's chat_id is uniform across
 * chat types, so no per-type branching was ever needed here for "reply to
 * the right chat". What Phase 9.0 actually added: every reply now threads
 * via reply_to_message_id (update.messageId) instead of posting as a bare
 * new message — this matters most in a busy group, where it's otherwise
 * ambiguous which voice note a translation belongs to.
 *
 * LanguagePair.DEFAULT_MVP_PAIR is still the single global default for
 * every chat type — no group-specific or per-sender language logic here;
 * that is out of scope for this phase (see TelegramChatType's doc comment).
 */
class VoiceMessageHandler @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val translationRepository: TranslationRepository,
    private val sendTelegramMessage: SendTelegramMessageUseCase,
    private val cacheManager: CacheManager,
    private val logger: BridgeLogger
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean {
        val claims = update.voiceFileId != null
        // --- TEMPORARY DIAGNOSTIC (remove after root cause found) ---
        logger.debug(TAG, "VoiceMessageHandler.canHandle update_id=${update.updateId} chat.type=${update.chatType} -> $claims")
        // --- END TEMPORARY DIAGNOSTIC ---
        return claims
    }

    override suspend fun handle(update: TelegramUpdate) {
        // --- TEMPORARY DIAGNOSTIC (remove after root cause found) ---
        logger.debug(TAG, "VoiceMessageHandler.handle ENTERED for update_id=${update.updateId} chat.id=${update.chatId} chat.type=${update.chatType}")
        // --- END TEMPORARY DIAGNOSTIC ---

        val fileId = update.voiceFileId ?: return

        val voiceFile = downloadRepository.downloadVoice(fileId).getOrElse { error ->
            logger.error(TAG, "Download failed for update ${update.updateId}", error)
            sendTelegramMessage(update.chatId, errorReply(), update.messageId)
            return
        }

        try {
            val request = TranslationRequest(
                mode = TranslationMode.VOICE,
                languagePair = LanguagePair.DEFAULT_MVP_PAIR,
                sourceFile = voiceFile
            )

            translationRepository.translate(request)
                .onSuccess { result -> sendTelegramMessage(update.chatId, result.translatedText, update.messageId) }
                .onFailure { error -> sendTelegramMessage(update.chatId, errorReply(), update.messageId) }
        } finally {
            cacheManager.delete(voiceFile)
        }
    }

    /**
     * Generic and non-technical on purpose — the full exception is already
     * logged (here for download failures, inside GeminiTranslationRepository
     * for translation failures), so nothing is lost by not echoing a raw
     * exception class/message into a group chat full of other people.
     */
    private fun errorReply(): String =
        "Sorry, I couldn't translate that voice message. Please try again in a moment."

    private companion object {
        const val TAG = "SigmaBridge"
    }
}
