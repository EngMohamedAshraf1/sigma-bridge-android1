package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Tiny relay. Conversation topic and encryption key are derived from the two user IDs. */
@Singleton
class NtfyChatRepository @Inject constructor(
    private val baseClient: OkHttpClient,
    private val json: Json,
    private val crypto: ChatCrypto
) : ChatRepository {
    private val streamClient by lazy { baseClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> = runCatching {
        val encryptedMessage = message.copy(text = crypto.encrypt(message.text))
        val body = json.encodeToString(ChatMessage.serializer(), encryptedMessage).toRequestBody(JSON_MEDIA_TYPE)
        withContext(Dispatchers.IO) {
            baseClient.newCall(Request.Builder().url("$BASE_URL/${topic.trim()}").post(body).build()).execute().use { response ->
                check(response.isSuccessful) { "Chat relay returned HTTP ${response.code}" }
            }
        }
    }

    override fun observe(topic: String, ownSenderId: String): Flow<ChatMessage> = callbackFlow {
        val normalizedTopic = topic.trim()
        val worker = CoroutineScope(Dispatchers.IO).launch {
            var retryDelayMs = INITIAL_RETRY_MS
            while (isActive) {
                val request = Request.Builder().url("$BASE_URL/$normalizedTopic/json?since=10m").get().build()
                val call = streamClient.newCall(request)
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "Chat relay returned HTTP ${response.code}" }
                        val body = response.body ?: error("Chat relay returned an empty response")
                        retryDelayMs = INITIAL_RETRY_MS
                        body.byteStream().bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (!isActive || line.isBlank()) return@forEach
                                val envelope = runCatching { json.decodeFromString(NtfyEnvelope.serializer(), line) }.getOrNull() ?: return@forEach
                                if (envelope.event != "message" || envelope.message.isNullOrBlank()) return@forEach
                                val message = runCatching { json.decodeFromString(ChatMessage.serializer(), envelope.message) }.getOrNull() ?: return@forEach
                                if (message.senderId == ownSenderId) return@forEach
                                val decrypted = runCatching { message.copy(text = crypto.decrypt(message.text)) }.getOrNull() ?: return@forEach
                                trySend(decrypted)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    if (!isActive) break
                    delay(retryDelayMs)
                    retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_MS)
                }
            }
        }
        awaitClose { worker.cancel() }
    }

    fun createMessage(senderId: String, text: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(), senderId = senderId, text = text, createdAt = System.currentTimeMillis()
    )

    @kotlinx.serialization.Serializable
    private data class NtfyEnvelope(val event: String, val message: String? = null)

    private companion object {
        const val BASE_URL = "https://ntfy.sh"
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
