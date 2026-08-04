package com.sigmabridge.app.data.telegram.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramGetUpdatesResponseDto(
    val ok: Boolean,
    val result: List<TelegramUpdateDto> = emptyList()
)

@Serializable
data class TelegramUpdateDto(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessageDto? = null
)

@Serializable
data class TelegramMessageDto(
    @SerialName("message_id") val messageId: Long,
    val chat: TelegramChatDto,
    val from: TelegramUserDto? = null,
    val voice: TelegramVoiceDto? = null
)

@Serializable
data class TelegramChatDto(
    val id: Long,
    val type: String? = null
)

@Serializable
data class TelegramUserDto(
    val id: Long
)

@Serializable
data class TelegramVoiceDto(
    @SerialName("file_id") val fileId: String
)

@Serializable
data class TelegramSendMessageRequestDto(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_to_message_id") val replyToMessageId: Long? = null
)

@Serializable
data class TelegramSendMessageResponseDto(
    val ok: Boolean
)

@Serializable
data class TelegramGetFileResponseDto(
    val ok: Boolean,
    val result: TelegramFileDto? = null
)

@Serializable
data class TelegramFileDto(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_path") val filePath: String? = null
)
