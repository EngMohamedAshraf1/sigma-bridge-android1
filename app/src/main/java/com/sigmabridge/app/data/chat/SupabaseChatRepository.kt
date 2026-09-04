package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatEvent
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReceipt
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.ChatReceiptType
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseChatRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager,
    private val identity: ChatIdentity,
    private val crypto: ChatCrypto
) : ChatRepository {

    private var cachedDeviceId: String? = null
    private var cachedConversationId: String? = null
    private val prepareMutex = Mutex()
    private val wireJson = Json { ignoreUnknownKeys = true }

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> = runCatching {
        require(topic == identity.conversationTopic()) {
            "Supabase topic does not match the active partner."
        }
        val userId = prepareConversation()
        require(userId == sessionManager.currentUserId()) {
            "Supabase session changed unexpectedly."
        }
        val hasReply = message.replyTo != null
        val plaintext = if (hasReply) {
            wireJson.encodeToString(
                ChatMessageWirePayload.serializer(),
                ChatMessageWirePayload(
                    type = ChatMessageWirePayload.TYPE,
                    text = message.text,
                    replyTo = message.replyTo
                )
            )
        } else {
            message.text
        }
        val encrypted = crypto.encrypt(plaintext)
        supabase.postgrest.rpc(
            "sigma_send_message",
            SendMessageRpcParams(
                conversationKey = identity.conversationKeyHex(),
                clientMessageId = UUID.fromString(message.id).toString(),
                senderDeviceId = cachedDeviceId ?: error("Supabase device is not registered."),
                ciphertext = encrypted,
                nonce = crypto.nonceFromEncrypted(encrypted),
                messageVersion = if (hasReply) 2 else 1
            )
        ).decodeAs<SupabaseMessageRow>()
    }

    override suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        setReceipt(receipt.messageId, delivered = true, read = false)

    override suspend fun sendReadReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        setReceipt(receipt.messageId, delivered = true, read = true)

    private suspend fun setReceipt(
        messageId: String,
        delivered: Boolean,
        read: Boolean
    ): Result<Unit> = runCatching {
        val clientMessageId = UUID.fromString(messageId).toString()
        prepareConversation()
        val conversationId = cachedConversationId
            ?: error("Supabase conversation is not initialized.")
        val serverMessageId = supabase.postgrest
            .from("messages")
            .select {
                filter {
                    eq("conversation_id", conversationId)
                    eq("client_message_id", clientMessageId)
                }
            }
            .decodeSingleOrNull<SupabaseMessageRow>()
            ?.id
            ?: error("Supabase message was not found for receipt.")

        supabase.postgrest.rpc(
            "sigma_set_receipt",
            SetReceiptRpcParams(
                messageId = UUID.fromString(serverMessageId).toString(),
                delivered = delivered,
                read = read
            )
        ).decodeAs<SupabaseReceiptRow>()
    }

    /**
     * Background-only observer used by ChatNotificationService.
     * It deliberately uses the same PostgREST polling path as the working
     * foreground transport, avoiding a second Realtime consumer that can
     * interfere with message visibility while keeping the foreground path intact.
     */
    fun observeRealtimeEvents(partnerId: String): Flow<ChatEvent> {
        val normalizedPartnerId = partnerId.trim()
        if (normalizedPartnerId.isBlank()) return emptyFlow()

        return channelFlow {
            val userId = sessionManager.ensureAnonymousSession().getOrThrow()
            val conversationId = ensureConversationForPartner(normalizedPartnerId)
            val knownMessageIds = mutableSetOf<String>()
            var lastSequence = 0L

            suspend fun fetchMessages(initial: Boolean) {
                val rows = supabase.postgrest
                    .from("messages")
                    .select {
                        filter {
                            eq("conversation_id", conversationId)
                            if (!initial) gt("sequence_number", lastSequence)
                        }
                    }
                    .decodeList<SupabaseMessageRow>()
                    .sortedBy { it.sequenceNumber }

                rows.forEach { row ->
                    if (!isActive || row.conversationId != conversationId) return@forEach
                    lastSequence = maxOf(lastSequence, row.sequenceNumber)
                    if (!knownMessageIds.add(row.id)) return@forEach
                    if (row.senderUserId == userId) return@forEach

                    val decrypted = runCatching { crypto.decrypt(row.ciphertext) }.getOrNull()
                        ?: return@forEach
                    val payload = decodeMessagePayload(decrypted)
                    send(
                        ChatEvent.Message(
                            ChatMessage(
                                id = row.clientMessageId,
                                senderId = normalizedPartnerId,
                                text = payload.text,
                                createdAt = parseTimestamp(row.createdAt),
                                deliveryStatus = MessageDeliveryStatus.DELIVERED,
                                replyTo = payload.replyTo
                            )
                        )
                    )
                }
            }

            fetchMessages(initial = true)
            while (isActive) {
                delay(BACKGROUND_POLL_INTERVAL_MS)
                runCatching { fetchMessages(initial = false) }
            }
        }
    }

    override fun observeEvents(topics: List<String>, ownSenderId: String): Flow<ChatEvent> {
        val normalized = topics.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return emptyFlow()
        val activeTopic = runCatching { identity.conversationTopic() }.getOrNull() ?: return emptyFlow()
        if (normalized.none { it == activeTopic }) return emptyFlow()

        return channelFlow {
            val preparedUserId = prepareConversation()
            val conversationId = cachedConversationId
                ?: error("Supabase conversation is not initialized.")
            val knownMessageIds = mutableSetOf<String>()
            var lastSequence = 0L

            suspend fun fetchMessages(initial: Boolean) {
                val rows = supabase.postgrest.from("messages").select {
                    filter {
                        eq("conversation_id", conversationId)
                        if (!initial) gt("sequence_number", lastSequence)
                    }
                }.decodeList<SupabaseMessageRow>().sortedBy { it.sequenceNumber }

                rows.forEach { row ->
                    if (!isActive || row.conversationId != conversationId) return@forEach
                    lastSequence = maxOf(lastSequence, row.sequenceNumber)
                    if (!knownMessageIds.add(row.id)) return@forEach
                    val decrypted = runCatching { crypto.decrypt(row.ciphertext) }.getOrNull()
                        ?: return@forEach
                    val payload = decodeMessagePayload(decrypted)
                    val senderId = if (row.senderUserId == preparedUserId) identity.myId else identity.partnerId
                    val status = if (row.senderUserId == preparedUserId) MessageDeliveryStatus.SENT else MessageDeliveryStatus.DELIVERED
                    send(
                        ChatEvent.Message(
                            ChatMessage(
                                id = row.clientMessageId,
                                senderId = senderId,
                                text = payload.text,
                                createdAt = parseTimestamp(row.createdAt),
                                deliveryStatus = status,
                                replyTo = payload.replyTo
                            )
                        )
                    )
                }
            }

            suspend fun fetchReceipts() {
                val rows = supabase.postgrest
                    .from("message_receipts")
                    .select()
                    .decodeList<SupabaseReceiptRow>()

                rows.forEach { row ->
                    if (!isActive || row.userId == preparedUserId) return@forEach
                    val serverMessage = supabase.postgrest
                        .from("messages")
                        .select {
                            filter {
                                eq("id", row.messageId)
                                eq("conversation_id", conversationId)
                            }
                        }
                        .decodeSingleOrNull<SupabaseMessageRow>()
                        ?: return@forEach
                    val clientMessageId = serverMessage.clientMessageId
                    when {
                        row.readAt != null -> send(
                            ChatEvent.Read(
                                ChatReceipt(
                                    messageId = clientMessageId,
                                    senderId = identity.partnerId,
                                    type = ChatReceiptType.READ
                                )
                            )
                        )
                        row.deliveredAt != null -> send(
                            ChatEvent.Delivered(
                                ChatReceipt(
                                    messageId = clientMessageId,
                                    senderId = identity.partnerId,
                                    type = ChatReceiptType.DELIVERED
                                )
                            )
                        )
                    }
                }
            }

            fetchMessages(initial = true)
            fetchReceipts()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                runCatching {
                    fetchMessages(initial = false)
                    fetchReceipts()
                }
            }
        }
    }

    private fun decodeMessagePayload(value: String): ChatMessageWirePayload {
        return runCatching {
            val payload = wireJson.decodeFromString<ChatMessageWirePayload>(value)
            if (payload.type == ChatMessageWirePayload.TYPE) payload
            else ChatMessageWirePayload(type = ChatMessageWirePayload.TYPE, text = value, replyTo = null)
        }.getOrElse {
            ChatMessageWirePayload(type = ChatMessageWirePayload.TYPE, text = value, replyTo = null)
        }
    }

    private suspend fun ensureConversationForPartner(partnerId: String): String =
        supabase.postgrest.rpc(
            "sigma_ensure_conversation",
            EnsureConversationRpcParams(
                partnerPublicId = partnerId,
                conversationKey = identity.conversationKeyFor(partnerId)
                    .joinToString("") { "%02x".format(it) }
            )
        ).decodeAs<String>()

    private suspend fun prepareConversation(): String = prepareMutex.withLock {
        val userId = sessionManager.ensureAnonymousSession().getOrThrow()
        if (cachedDeviceId == null) cachedDeviceId = registerDeviceWithRecovery()
        if (cachedConversationId == null) {
            cachedConversationId = supabase.postgrest.rpc(
                "sigma_ensure_conversation",
                EnsureConversationRpcParams(
                    partnerPublicId = identity.partnerId,
                    conversationKey = identity.conversationKeyHex()
                )
            ).decodeAs<String>()
        }
        userId
    }

    private suspend fun registerDeviceWithRecovery(): String {
        for (attempt in 0 until 2) {
            try {
                val result = supabase.postgrest.rpc(
                    "sigma_register_device",
                    RegisterDeviceRpcParams(
                        publicId = identity.myId,
                        devicePublicId = identity.devicePublicId,
                        identityPublicKey = identity.legacyIdentityKey
                    )
                ).decodeList<RegisterDeviceRpcResult>().firstOrNull()
                    ?: error("Supabase device registration returned no device.")
                return result.deviceId
            } catch (error: Throwable) {
                val isPublicIdConflict = error.message
                    ?.contains("PUBLIC_ID_ALREADY_IN_USE", ignoreCase = true) == true
                if (attempt == 0 && isPublicIdConflict) {
                    identity.regenerateMyId()
                    continue
                }
                throw error
            }
        }
        error("Supabase device registration failed after identity recovery.")
    }

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val BACKGROUND_POLL_INTERVAL_MS = 2_000L
    }
}
