package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.data.network.await
import com.sigmabridge.app.data.telegram.dto.TelegramAnswerCallbackQueryRequestDto
import com.sigmabridge.app.data.telegram.dto.TelegramEditMessageTextRequestDto
import com.sigmabridge.app.data.telegram.dto.TelegramGetChatAdministratorsResponseDto
import com.sigmabridge.app.data.telegram.dto.TelegramGetUpdatesResponseDto
import com.sigmabridge.app.data.telegram.dto.TelegramInlineKeyboardButtonDto
import com.sigmabridge.app.data.telegram.dto.TelegramInlineKeyboardMarkupDto
import com.sigmabridge.app.data.telegram.dto.TelegramSendMessageRequestDto
import com.sigmabridge.app.data.telegram.dto.TelegramSendMessageResponseDto
import com.sigmabridge.app.data.telegram.dto.TelegramUpdateDto
import com.sigmabridge.app.domain.model.TelegramKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/** [httpCode] is null for a response that parsed but reported ok=false; set whenever the HTTP status itself indicates the failure. */
class TelegramApiException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Uses Call.await() (data/network/OkHttpCallExtensions.kt) instead of the
 * blocking execute() — cancelling the coroutine calling these functions now
 * actually aborts the in-flight HTTP call instead of waiting for it to
 * finish on its own. withContext(Dispatchers.IO) is kept so that response
 * parsing after the call resumes runs on the IO dispatcher, same as before.
 */
class TelegramApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    suspend fun getUpdates(
        botToken: String,
        offset: Long?,
        timeoutSeconds: Int
    ): List<TelegramUpdateDto> = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/getUpdates".toHttpUrl().newBuilder()
            .addQueryParameter("timeout", timeoutSeconds.toString())
            .apply { offset?.let { addQueryParameter("offset", it.toString()) } }
            .build()

        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("Telegram getUpdates failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(TelegramGetUpdatesResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("Telegram getUpdates returned ok=false")
            }
            parsed.result
        }
    }

    suspend fun sendMessage(
        botToken: String,
        chatId: Long,
        text: String,
        replyToMessageId: Long? = null,
        keyboard: TelegramKeyboard? = null
    ) = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/sendMessage".toHttpUrl()
        val requestBody = json.encodeToString(
            TelegramSendMessageRequestDto.serializer(),
            TelegramSendMessageRequestDto(
                chatId = chatId,
                text = text,
                replyToMessageId = replyToMessageId,
                replyMarkup = keyboard.toDto()
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody).build()

        httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("Telegram sendMessage failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(TelegramSendMessageResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("Telegram sendMessage returned ok=false")
            }
        }
    }

    /** Used by the interactive language flow (Phase 9.8) to step through menu -> source -> target in place. */
    suspend fun editMessageText(
        botToken: String,
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: TelegramKeyboard? = null
    ) = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/editMessageText".toHttpUrl()
        val requestBody = json.encodeToString(
            TelegramEditMessageTextRequestDto.serializer(),
            TelegramEditMessageTextRequestDto(
                chatId = chatId,
                messageId = messageId,
                text = text,
                replyMarkup = keyboard.toDto()
            )
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody).build()

        httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("Telegram editMessageText failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(TelegramSendMessageResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("Telegram editMessageText returned ok=false")
            }
        }
    }

    /** Required by Telegram to stop the tapped button's client-side loading spinner. */
    suspend fun answerCallbackQuery(botToken: String, callbackQueryId: String) = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/answerCallbackQuery".toHttpUrl()
        val requestBody = json.encodeToString(
            TelegramAnswerCallbackQueryRequestDto.serializer(),
            TelegramAnswerCallbackQueryRequestDto(callbackQueryId = callbackQueryId)
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody).build()

        httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("Telegram answerCallbackQuery failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(TelegramSendMessageResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("Telegram answerCallbackQuery returned ok=false")
            }
        }
    }

    /** Used only by the chat-admin permission check (Phase 9.3) - not part of the translation pipeline. */
    suspend fun getChatAdministrators(botToken: String, chatId: Long): List<Long> = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/getChatAdministrators".toHttpUrl().newBuilder()
            .addQueryParameter("chat_id", chatId.toString())
            .build()

        httpClient.newCall(Request.Builder().url(url).build()).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("getChatAdministrators failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(TelegramGetChatAdministratorsResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("getChatAdministrators returned ok=false")
            }
            parsed.result.map { it.user.id }
        }
    }

    private fun TelegramKeyboard?.toDto(): TelegramInlineKeyboardMarkupDto? {
        if (this == null) return null
        return TelegramInlineKeyboardMarkupDto(
            inlineKeyboard = rows.map { row ->
                row.map { button -> TelegramInlineKeyboardButtonDto(text = button.text, callbackData = button.callbackData) }
            }
        )
    }
}
