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
    val message: TelegramMessageDto? = null,
    @SerialName("callback_query") val callbackQuery: TelegramCallbackQueryDto? = null
)

@Serializable
data class TelegramCallbackQueryDto(
    val id: String,
    val from: TelegramUserDto,
    val message: TelegramMessageDto? = null,
    val data: String? = null
)

@Serializable
data class TelegramMessageDto(
    @SerialName("message_id") val messageId: Long,
    val chat: TelegramChatDto,
    val from: TelegramUserDto? = null,
    val voice: TelegramVoiceDto? = null,
    val audio: TelegramAudioDto? = null,
    val text: String? = null
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
data class TelegramAudioDto(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null
)

@Serializable
data class TelegramGetChatAdministratorsResponseDto(
    val ok: Boolean,
    val result: List<TelegramChatMemberDto> = emptyList()
)

@Serializable
data class TelegramChatMemberDto(
    val user: TelegramUserDto,
    val status: String
)

@Serializable
data class TelegramInlineKeyboardMarkupDto(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<TelegramInlineKeyboardButtonDto>>
)

@Serializable
data class TelegramInlineKeyboardButtonDto(
    val text: String,
    @SerialName("callback_data") val callbackData: String
)

@Serializable
data class TelegramSendMessageRequestDto(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_to_message_id") val replyToMessageId: Long? = null,
    @SerialName("reply_markup") val replyMarkup: TelegramInlineKeyboardMarkupDto? = null
)

@Serializable
data class TelegramSendMessageResponseDto(
    val ok: Boolean
)

@Serializable
data class TelegramEditMessageTextRequestDto(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    val text: String,
    @SerialName("reply_markup") val replyMarkup: TelegramInlineKeyboardMarkupDto? = null
)

@Serializable
data class TelegramAnswerCallbackQueryRequestDto(
    @SerialName("callback_query_id") val callbackQueryId: String
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
