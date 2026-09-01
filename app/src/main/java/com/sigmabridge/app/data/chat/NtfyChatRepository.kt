package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatEvent
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReceipt
import com.sigmabridge.app.domain.chat.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny relay. Conversation messages and delivery receipts use separate ntfy topics so a
 * delivery receipt can never be confused with a normal chat message/notification.
 */
@Singleton
class NtfyChatRepository @Inject constructor(
    private val baseClient: OkHttpClient,
    private val json: Json,
    private val crypto: ChatCrypto
) : ChatRepository {
    private val streamClient by lazy { baseClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = ConcurrentHashMap<String, RelayStream>()

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> = runCatching {
        sendWire(topic, message.copy(text = crypto.encrypt(message.text)))
    }

    override suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit> = runCatching {
        val payload = RECEIPT_PREFIX + json.encodeToString(ChatReceipt.serializer(), receipt)
        val message = ChatMessage(
            id = "receipt-${receipt.messageId}-${UUID.randomUUID()}",
            senderId = receipt.senderId,
            text = crypto.encrypt(payload),
            createdAt = System.currentTimeMillis()
        )
        sendWire(receiptTopic(topic), message)
    }

    /**
     * Subscribe to both the normal message topic and the dedicated receipt topic.
     * They are merged only inside the repository; receipts never travel through the
     * normal message/notification path.
     */
    override fun observeEvents(topic: String, ownSenderId: String): Flow<ChatEvent> {
        val normalizedTopic = topic.trim()
        val messageStream = streams.computeIfAbsent(normalizedTopic) { createRelayStream(normalizedTopic) }
        val receiptTopic = receiptTopic(normalizedTopic)
        val receiptStream = streams.computeIfAbsent(receiptTopic) { createRelayStream(receiptTopic) }
        return merge(messageStream.events.asSharedFlow(), receiptStream.events.asSharedFlow())
    }

    private fun createRelayStream(topic: String): RelayStream {
        val events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
        val job = relayScope.launch {
            var retryDelayMs = INITIAL_RETRY_MS
            while (isActive) {
                val request = Request.Builder().url("$BASE_URL/$topic/json?since=10m").get().build()
                val call = streamClient.newCall(request)
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "Chat relay returned HTTP ${response.code}" }
                        val body = response.body ?: error("Chat relay returned an empty response")
                        retryDelayMs = INITIAL_RETRY_MS
                        body.byteStream().bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (!isActive || line.isBlank()) return@forEach
                                val envelope = runCatching {
                                    json.decodeFromString(NtfyEnvelope.serializer(), line)
                                }.getOrNull() ?: return@forEach
                                if (envelope.event != "message" || envelope.message.isNullOrBlank()) return@forEach

                                val wireMessage = runCatching {
                                    json.decodeFromString(ChatMessage.serializer(), envelope.message)
                                }.getOrNull() ?: return@forEach

                                val decryptedText = runCatching { crypto.decrypt(wireMessage.text) }.getOrNull()
                                    ?: return@forEach

                                if (decryptedText.startsWith(RECEIPT_PREFIX)) {
                                    val receipt = runCatching {
                                        json.decodeFromString(
                                            ChatReceipt.serializer(),
                                            decryptedText.removePrefix(RECEIPT_PREFIX)
                                        )
                                    }.getOrNull() ?: return@forEach

                                    if (receipt.senderId != wireMessage.senderId) return@forEach
                                    events.tryEmit(ChatEvent.Delivered(receipt))
                                } else {
                                    events.tryEmit(ChatEvent.Message(wireMessage.copy(text = decryptedText)))
                                }
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
        return RelayStream(events, job)
    }

    private fun receiptTopic(topic: String): String = "${topic.trim()}-receipts"

    private suspend fun sendWire(topic: String, message: ChatMessage) {
        val body = json.encodeToString(ChatMessage.serializer(), message).toRequestBody(JSON_MEDIA_TYPE)
        withContext(Dispatchers.IO) {
            baseClient.newCall(Request.Builder().url("$BASE_URL/${topic.trim()}").post(body).build()).execute().use { response ->
                check(response.isSuccessful) { "Chat relay returned HTTP ${response.code}" }
            }
        }
    }

    fun createMessage(senderId: String, text: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(), senderId = senderId, text = text, createdAt = System.currentTimeMillis()
    )

    private data class RelayStream(
        val events: MutableSharedFlow<ChatEvent>,
        val job: Job
    )

    @Serializable
    private data class NtfyEnvelope(val event: String, val message: String? = null)

    private companion object {
        const val BASE_URL = "https://ntfy.sh"
        const val RECEIPT_PREFIX = "__SIGMA_DELIVERED__"
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
