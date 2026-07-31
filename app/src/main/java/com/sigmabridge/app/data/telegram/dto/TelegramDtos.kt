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
    val voice: TelegramVoiceDto? = null
)

@Serializable
data class TelegramChatDto(
    val id: Long
)

@Serializable
data class TelegramVoiceDto(
    @SerialName("file_id") val fileId: String
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
