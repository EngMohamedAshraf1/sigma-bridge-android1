package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.data.assistant.AssistantSessionStore
import com.sigmabridge.app.data.assistant.GeminiAssistantClient
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.TelegramChatType
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Claims only explicit text requests in Telegram groups.
 * Media messages are always left to the existing media pipeline.
 */
class AssistantMessageHandler @Inject constructor(
    private val assistantClient: GeminiAssistantClient,
    private val sessionStore: AssistantSessionStore,
    private val sendTelegramMessage: SendTelegramMessageUseCase,
    private val logger: BridgeLogger
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean {
        if (update.chatType != TelegramChatType.GROUP &&
            update.chatType != TelegramChatType.SUPERGROUP
        ) return false
        if (update.callbackQueryId != null || update.containsMedia) return false

        val rawText = update.assistantText ?: return false
        if (!MENTION_REGEX.containsMatchIn(rawText)) return false

        val request = stripMention(rawText)
        if (request.isBlank()) return false
        // Keep explicit slash commands, including the existing language commands, out of the assistant.
        if (request.startsWith("/") && !request.equals(NEW_SESSION_COMMAND, ignoreCase = true)) return false

        return true
    }

    override suspend fun handle(update: TelegramUpdate) {
        val rawText = update.assistantText ?: return
        val request = stripMention(rawText)
        if (request.isBlank()) return

        if (request.equals(NEW_SESSION_COMMAND, ignoreCase = true)) {
            sessionStore.clear(update.chatId)
            sendTelegramMessage(
                update.chatId,
                "بدأنا موضوعًا جديدًا. اذكر @sigma_bridge_bot مع سؤالك للبدء.",
                update.messageId
            )
            return
        }

        val currentMessage = buildString {
            update.repliedMessageText?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("Message being replied to (text only):")
                appendLine(it)
                appendLine()
            }
            if (update.repliedMessageContainsMedia && update.repliedMessageText.isNullOrBlank()) {
                appendLine("The replied-to Telegram message contains media, but its media content is not available to you.")
                appendLine()
            }
            appendLine("Group member request:")
            append(request)
        }

        try {
            val history = sessionStore.history(update.chatId)
            val answer = assistantClient.respond(history, currentMessage)
            sessionStore.remember(update.chatId, currentMessage, answer)
            sendLongReply(update, answer)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.error(TAG, "Text assistant failed for Telegram update ${update.updateId}", error)
            sendTelegramMessage(
                update.chatId,
                "لم أتمكن من إكمال الرد الآن. حاول مرة أخرى بعد قليل.",
                update.messageId
            )
        }
    }

    private suspend fun sendLongReply(update: TelegramUpdate, text: String) {
        var remaining = text.trim()
        var first = true
        while (remaining.isNotEmpty()) {
            if (remaining.length <= MAX_TELEGRAM_TEXT_LENGTH) {
                sendTelegramMessage(update.chatId, remaining, if (first) update.messageId else null)
                return
            }

            val newline = remaining.lastIndexOf('\n', MAX_TELEGRAM_TEXT_LENGTH)
            val space = remaining.lastIndexOf(' ', MAX_TELEGRAM_TEXT_LENGTH)
            val cut = when {
                newline >= MAX_SAFE_SPLIT -> newline
                space >= MAX_SAFE_SPLIT -> space
                else -> MAX_TELEGRAM_TEXT_LENGTH
            }
            sendTelegramMessage(
                update.chatId,
                remaining.substring(0, cut).trimEnd(),
                if (first) update.messageId else null
            )
            remaining = remaining.substring(cut).trimStart()
            first = false
        }
    }

    private fun stripMention(text: String): String =
        MENTION_REGEX.replace(text, " ").trim()

    private companion object {
        const val TAG = "SigmaBridgeAssistant"
        const val NEW_SESSION_COMMAND = "/new"
        const val MAX_TELEGRAM_TEXT_LENGTH = 3900
        const val MAX_SAFE_SPLIT = 1800
        val MENTION_REGEX = Regex("@sigma_bridge_bot(?=\\s|$|[.,!?،؛:])", RegexOption.IGNORE_CASE)
    }
}
