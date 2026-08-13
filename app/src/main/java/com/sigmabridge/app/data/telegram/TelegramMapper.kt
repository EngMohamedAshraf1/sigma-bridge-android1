package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.data.telegram.dto.TelegramUpdateDto
import com.sigmabridge.app.domain.model.TelegramChatType
import com.sigmabridge.app.domain.model.TelegramUpdate

/**
 * A message-based update and a callback_query-based update (Phase 9.8,
 * an inline keyboard button tap) both map to the same TelegramUpdate,
 * mirroring how voice updates and text-command updates already share it —
 * only one of {voiceFileId/messageText} vs {callbackQueryId/callbackData}
 * is populated at a time. An update with neither a `message` nor a
 * `callback_query` (e.g. edited_message, channel_post — not handled yet)
 * maps to null and is dropped by the caller.
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
            messageText = chatMessage.text,
            callbackQueryId = null,
            callbackData = null
        )

        callbackQuery != null -> {
            // The message the tapped keyboard is attached to - needed so the
            // handler can edit it in place. Absent only if that message was
            // deleted; a callback_query with no attached message is dropped,
            // same as a message-less update.
            val attachedMessage = callbackQuery.message ?: return null
            TelegramUpdate(
                updateId = updateId,
                chatId = attachedMessage.chat.id,
                messageId = attachedMessage.messageId,
                chatType = attachedMessage.chat.type.toChatType(),
                senderUserId = callbackQuery.from.id,
                voiceFileId = null,
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
