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
        chatMessage != null -> {
            val commandText = chatMessage.text ?: chatMessage.caption
            val requested = commandText.hasTranslateCommand()

            val directVideo = chatMessage.video?.let { video ->
                VideoTarget(
                    fileId = video.fileId,
                    mimeType = video.mimeType,
                    fileName = video.fileName,
                    fileSizeBytes = video.fileSize,
                    messageId = chatMessage.messageId
                )
            } ?: chatMessage.videoNote?.let { note ->
                VideoTarget(
                    fileId = note.fileId,
                    mimeType = "video/mp4",
                    fileName = null,
                    fileSizeBytes = note.fileSize,
                    messageId = chatMessage.messageId
                )
            }

            val repliedVideo = chatMessage.replyToMessage?.let { reply ->
                reply.video?.let { video ->
                    VideoTarget(
                        fileId = video.fileId,
                        mimeType = video.mimeType,
                        fileName = video.fileName,
                        fileSizeBytes = video.fileSize,
                        messageId = reply.messageId
                    )
                } ?: reply.videoNote?.let { note ->
                    VideoTarget(
                        fileId = note.fileId,
                        mimeType = "video/mp4",
                        fileName = null,
                        fileSizeBytes = note.fileSize,
                        messageId = reply.messageId
                    )
                }
            }

            val targetVideo = when {
                requested && directVideo != null -> directVideo
                requested && repliedVideo != null -> repliedVideo
                else -> null
            }

            TelegramUpdate(
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
                videoFileId = targetVideo?.fileId,
                videoMimeType = targetVideo?.mimeType,
                videoFileName = targetVideo?.fileName,
                videoFileSizeBytes = targetVideo?.fileSizeBytes,
                videoMessageId = targetVideo?.messageId,
                videoTranslateRequested = targetVideo != null,
                messageText = chatMessage.text ?: chatMessage.caption,
                callbackQueryId = null,
                callbackData = null
            )
        }

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
                videoFileId = null,
                videoMimeType = null,
                videoFileName = null,
                videoFileSizeBytes = null,
                videoMessageId = null,
                videoTranslateRequested = false,
                messageText = null,
                callbackQueryId = callbackQuery.id,
                callbackData = callbackQuery.data
            )
        }

        else -> null
    }
}

private data class VideoTarget(
    val fileId: String,
    val mimeType: String?,
    val fileName: String?,
    val fileSizeBytes: Long?,
    val messageId: Long
)

private fun String?.hasTranslateCommand(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return false
    return Regex("(^|\\s)/translate(?:@[A-Za-z0-9_]+)?(?:\\s|$)", RegexOption.IGNORE_CASE)
        .containsMatchIn(value)
}

private fun String?.toChatType(): TelegramChatType = when (this) {
    "private" -> TelegramChatType.PRIVATE
    "group" -> TelegramChatType.GROUP
    "supergroup" -> TelegramChatType.SUPERGROUP
    "channel" -> TelegramChatType.CHANNEL
    else -> TelegramChatType.UNKNOWN
}
