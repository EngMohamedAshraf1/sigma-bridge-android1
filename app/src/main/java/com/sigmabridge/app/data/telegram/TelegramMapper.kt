package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.data.telegram.dto.TelegramUpdateDto
import com.sigmabridge.app.domain.model.TelegramChatType
import com.sigmabridge.app.domain.model.TelegramUpdate

/**
 * A message-based update and a callback_query-based update both map to the
 * same TelegramUpdate. Message media preserves voice and Telegram audio
 * metadata so the dispatch layer can handle them independently.
 */
fun TelegramUpdateDto.toDomain(): TelegramUpdate? {
    val chatMessage = message
    val callbackQuery = callbackQuery

    return when {
        chatMessage != null -> TelegramUpdate(
            updateId = updateId,
            chatId = chatMessage.chat.id,
            messageId = chatMessage.messageId,
            chatType = chatMessage.chat.type.toChatType(),
            senderUserId = chatMessage.from?.id,
            voiceFileId = chatMessage.voice?.fileId,
            audioFileId = chatMessage.audio?.fileId,
            audioMimeType = chatMessage.audio?.mimeType,
            audioFileName = chatMessage.audio?.fileName,
            audioFileSizeBytes = chatMessage.audio?.fileSize,
            messageText = chatMessage.text,
            callbackQueryId = null,
            callbackData = null
        )

        callbackQuery != null -> {
            val attachedMessage = callbackQuery.message ?: return null
            TelegramUpdate(
                updateId = updateId,
                chatId = attachedMessage.chat.id,
                messageId = attachedMessage.messageId,
                chatType = attachedMessage.chat.type.toChatType(),
                senderUserId = callbackQuery.from.id,
                voiceFileId = null,
                audioFileId = null,
                audioMimeType = null,
                audioFileName = null,
                audioFileSizeBytes = null,
                messageText = null,
                callbackQueryId = callbackQuery.id,
                callbackData = callbackQuery.data
            )
        }

        else -> null
    }
}

private fun String?.toChatType(): TelegramChatType = when (this) {
    "private" -> TelegramChatType.PRIVATE
    "group" -> TelegramChatType.GROUP
    "supergroup" -> TelegramChatType.SUPERGROUP
    "channel" -> TelegramChatType.CHANNEL
    else -> TelegramChatType.UNKNOWN
}
