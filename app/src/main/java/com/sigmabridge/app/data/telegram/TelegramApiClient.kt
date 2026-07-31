package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.data.telegram.dto.TelegramGetUpdatesResponseDto
import com.sigmabridge.app.data.telegram.dto.TelegramSendMessageRequestDto
import com.sigmabridge.app.data.telegram.dto.TelegramSendMessageResponseDto
import com.sigmabridge.app.data.telegram.dto.TelegramUpdateDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

class TelegramApiException(message: String) : Exception(message)

/**
 * OkHttp's synchronous `execute()` is a blocking call, not a callback — run
 * on Dispatchers.IO inside a suspend function, it behaves exactly like any
 * other suspending I/O call. No Callback/Call.enqueue() anywhere, per the
 * "coroutines + Flow, not callbacks" requirement.
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

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("Telegram getUpdates failed: HTTP ${response.code} — $body")
            }
            val parsed = json.decodeFromString(TelegramGetUpdatesResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("Telegram getUpdates returned ok=false")
            }
            parsed.result
        }
    }

    suspend fun sendMessage(botToken: String, chatId: Long, text: String) = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/sendMessage".toHttpUrl()
        val requestBody = json.encodeToString(
            TelegramSendMessageRequestDto.serializer(),
            TelegramSendMessageRequestDto(chatId = chatId, text = text)
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody).build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("Telegram sendMessage failed: HTTP ${response.code} — $body")
            }
            val parsed = json.decodeFromString(TelegramSendMessageResponseDto.serializer(), body)
            if (!parsed.ok) {
                throw TelegramApiException("Telegram sendMessage returned ok=false")
            }
        }
    }
}
