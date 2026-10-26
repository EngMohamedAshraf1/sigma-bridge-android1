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
            val requested = commandText.hasSigmaBridgeMention()
            val requestHasText = commandText.withoutSigmaBridgeMention().any { !it.isWhitespace() && it !in ".!?,،؛:" }
            val currentMessageHasMedia = chatMessage.voice != null ||
                chatMessage.audio != null || chatMessage.photo != null ||
                chatMessage.video != null || chatMessage.videoNote != null ||
                chatMessage.document != null || chatMessage.animation != null ||
                chatMessage.sticker != null
            // Media attached to the current message stays in translation; a text reply with a
            // real request is conversation, even when it is replying to an older media message.
            val translateMentionedMedia = requested && (currentMessageHasMedia || !requestHasText)

            val directPhoto = chatMessage.photo
                ?.maxByOrNull { photo -> photo.width.toLong() * photo.height.toLong() }
                ?.let { photo ->
                    PhotoTarget(
                        fileId = photo.fileId,
                        fileSizeBytes = photo.fileSize
                    )
                }

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

            val repliedPhoto = chatMessage.replyToMessage?.photo
                ?.maxByOrNull { photo -> photo.width.toLong() * photo.height.toLong() }
                ?.let { photo ->
                    PhotoTarget(
                        fileId = photo.fileId,
                        fileSizeBytes = photo.fileSize
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

            val targetPhoto = when {
                translateMentionedMedia && directPhoto != null -> directPhoto
                translateMentionedMedia && repliedPhoto != null -> repliedPhoto
                else -> null
            }

            val targetVideo = when {
                translateMentionedMedia && directVideo != null -> directVideo
                translateMentionedMedia && repliedVideo != null -> repliedVideo
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
                photoFileId = targetPhoto?.fileId,
                photoFileSizeBytes = targetPhoto?.fileSizeBytes,
                photoMessageId = targetPhoto?.let {
                    if (directPhoto != null) chatMessage.messageId
                    else chatMessage.replyToMessage?.messageId
                },
                photoTranslateRequested = targetPhoto != null,
                videoFileId = targetVideo?.fileId,
                videoMimeType = targetVideo?.mimeType,
                videoFileName = targetVideo?.fileName,
                videoFileSizeBytes = targetVideo?.fileSizeBytes,
                videoMessageId = targetVideo?.messageId,
                videoTranslateRequested = targetVideo != null,
                messageText = chatMessage.text,
                callbackQueryId = null,
                callbackData = null,
                assistantText = chatMessage.text ?: chatMessage.caption,
                repliedMessageText = chatMessage.replyToMessage?.let { it.text ?: it.caption },
                containsMedia = currentMessageHasMedia,
                repliedMessageContainsMedia = chatMessage.replyToMessage?.let { reply ->
                    reply.photo != null || reply.video != null || reply.videoNote != null ||
                        reply.voice != null || reply.audio != null || reply.document != null ||
                        reply.animation != null || reply.sticker != null
                } == true
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
                photoFileId = null,
                photoFileSizeBytes = null,
                photoMessageId = null,
                photoTranslateRequested = false,
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

private data class PhotoTarget(
    val fileId: String,
    val fileSizeBytes: Long?
)

private data class VideoTarget(
    val fileId: String,
    val mimeType: String?,
    val fileName: String?,
    val fileSizeBytes: Long?,
    val messageId: Long
)

private val SIGMA_BRIDGE_MENTION = Regex("(^|\\s)@sigma_bridge_bot(?=\\s|$|[.,!?،؛:])", RegexOption.IGNORE_CASE)

private fun String?.hasSigmaBridgeMention(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return false
    return SIGMA_BRIDGE_MENTION.containsMatchIn(value)
}

private fun String?.withoutSigmaBridgeMention(): String =
    SIGMA_BRIDGE_MENTION.replace(this.orEmpty(), " ").trim()

private fun String?.toChatType(): TelegramChatType = when (this) {
    "private" -> TelegramChatType.PRIVATE
    "group" -> TelegramChatType.GROUP
    "supergroup" -> TelegramChatType.SUPERGROUP
    "channel" -> TelegramChatType.CHANNEL
    else -> TelegramChatType.UNKNOWN
}
