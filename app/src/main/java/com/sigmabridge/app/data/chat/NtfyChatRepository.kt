package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatEvent
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReceipt
import com.sigmabridge.app.domain.chat.ChatReceiptType
import com.sigmabridge.app.domain.chat.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Tiny relay for Private Chat. Telegram does not use this repository.
 *
 * Sending is deliberately serialized and coalesced by message ID so the foreground
 * ViewModel and the background service can never publish the same pending message twice
 * at the same time.
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
    private val publishMutex = Mutex()
    private val inFlightSends = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> {
        val deferred: CompletableDeferred<Result<Unit>>
        val owner: Boolean

        synchronized(inFlightSends) {
            val existing = inFlightSends[message.id]
            if (existing != null) {
                deferred = existing
                owner = false
            } else {
                deferred = CompletableDeferred()
                inFlightSends[message.id] = deferred
                owner = true
            }
        }

        if (!owner) return deferred.await()

        val result = runCatching {
            publishMutex.withLock {
                sendWire(topic, message.copy(text = crypto.encrypt(message.text)))
            }
        }

        synchronized(inFlightSends) {
            inFlightSends.remove(message.id)
        }
        deferred.complete(result)
        return result
    }

    override suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        sendReceipt(topic, receipt.copy(type = ChatReceiptType.DELIVERED))

    override suspend fun sendReadReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        sendReceipt(topic, receipt.copy(type = ChatReceiptType.READ))

    private suspend fun sendReceipt(topic: String, receipt: ChatReceipt): Result<Unit> = runCatching {
        val payload = RECEIPT_PREFIX + json.encodeToString(ChatReceipt.serializer(), receipt)
        val message = ChatMessage(
            id = "receipt-${receipt.messageId}-${receipt.type}-${UUID.randomUUID()}",
            senderId = receipt.senderId,
            text = crypto.encrypt(payload),
            createdAt = System.currentTimeMillis()
        )
        publishMutex.withLock {
            sendWire(receiptTopic(topic), message)
        }
    }

    override fun observeEvents(topics: List<String>, ownSenderId: String): Flow<ChatEvent> {
        val normalizedTopics = topics.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        if (normalizedTopics.isEmpty()) return emptyFlow()

        // One HTTP stream for all message topics and one for all receipt topics.
        val messageSubscription = normalizedTopics.joinToString(",")
        val receiptSubscription = normalizedTopics.map(::receiptTopic).joinToString(",")
        val messageStream = streams.computeIfAbsent(messageSubscription) { createRelayStream(messageSubscription) }
        val receiptStream = streams.computeIfAbsent(receiptSubscription) { createRelayStream(receiptSubscription) }
        return merge(messageStream.events.asSharedFlow(), receiptStream.events.asSharedFlow())
    }

    private fun createRelayStream(subscription: String): RelayStream {
        val events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
        val job = relayScope.launch {
            var retryDelayMs = INITIAL_RETRY_MS
            while (isActive) {
                val request = Request.Builder().url("$BASE_URL/${subscription}/json?since=10m").get().build()
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
                                    when (receipt.type) {
                                        ChatReceiptType.DELIVERED -> events.tryEmit(ChatEvent.Delivered(receipt))
                                        ChatReceiptType.READ -> events.tryEmit(ChatEvent.Read(receipt))
                                    }
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
        const val RECEIPT_PREFIX = "__SIGMA_RECEIPT__"
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
