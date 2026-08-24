package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/**
 * Phase 1 transport only.
 *
 * ntfy is used as a tiny public message relay. The topic is the room secret;
 * messages are JSON envelopes so the app can identify its sender and ignore
 * its own echoed publications. This phase intentionally contains no Gemini
 * translation and no authentication state.
 */
@Singleton
class NtfyChatRepository @Inject constructor(
    private val baseClient: OkHttpClient,
    private val json: Json
) : ChatRepository {

    private val streamClient by lazy {
        baseClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> = runCatching {
        val url = "$BASE_URL/${topic.trim()}"
        val body = json.encodeToString(ChatMessage.serializer(), message)
            .toRequestBody(JSON_MEDIA_TYPE)

        withContext(Dispatchers.IO) {
            val response = baseClient.newCall(
                Request.Builder()
                    .url(url)
                    .post(body)
                    .build()
            ).execute()

            response.use {
                check(it.isSuccessful) { "Chat relay returned HTTP ${it.code}" }
            }
        }
    }

    override fun observe(topic: String, ownSenderId: String): Flow<ChatMessage> = flow {
        val request = Request.Builder()
            .url("$BASE_URL/${topic.trim()}/json?since=10m")
            .get()
            .build()

        withContext(Dispatchers.IO) {
            streamClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Chat relay returned HTTP ${response.code}" }
                val body = response.body ?: error("Chat relay returned an empty response")

                body.byteStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val envelope = runCatching {
                            json.decodeFromString(NtfyEnvelope.serializer(), line)
                        }.getOrNull() ?: return@forEach

                        if (envelope.event != "message" || envelope.message.isNullOrBlank()) return@forEach

                        val message = runCatching {
                            json.decodeFromString(ChatMessage.serializer(), envelope.message)
                        }.getOrNull() ?: return@forEach

                        if (message.senderId != ownSenderId) emit(message)
                    }
                }
            }
        }
    }

    fun createMessage(senderId: String, text: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        senderId = senderId,
        text = text,
        createdAt = System.currentTimeMillis()
    )

    @kotlinx.serialization.Serializable
    private data class NtfyEnvelope(
        val event: String,
        val message: String? = null
    )

    private companion object {
        const val BASE_URL = "https://ntfy.sh"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
