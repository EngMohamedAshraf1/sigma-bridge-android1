package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.data.telegram.dto.TelegramUpdateDto
import com.sigmabridge.app.domain.model.TelegramUpdate

/**
 * Updates with no `message` (e.g. edited_message, channel_post — not
 * handled yet) map to null and are dropped by the caller. Keeps
 * "what an update means to this app" out of the DTO itself.
 */
fun TelegramUpdateDto.toDomain(): TelegramUpdate? {
    val chatMessage = message ?: return null
    return TelegramUpdate(
        updateId = updateId,
        chatId = chatMessage.chat.id,
        voiceFileId = chatMessage.voice?.fileId
    )
}
