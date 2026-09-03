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
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase transport for Private Chat.
 *
 * Telegram and Sigma Call do not use this repository.
 * Message bodies remain encrypted end-to-end; Supabase only relays ciphertext
 * and receipt metadata.
 *
 * Live delivery in the foreground uses lightweight PostgREST polling. A separate
 * Realtime observer is provided for the Private Chat background notification
 * service so the OS can receive live events while the UI is not open.
 */
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

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> = runCatching {
        require(topic == identity.conversationTopic()) { "Supabase topic does not match the active partner." }
        val userId = prepareConversation()
        require(userId == sessionManager.currentUserId()) { "Supabase session changed unexpectedly." }

        val encrypted = crypto.encrypt(message.text)
        supabase.postgrest.rpc(
            "sigma_send_message",
            SendMessageRpcParams(
                conversationKey = identity.conversationKeyHex(),
                clientMessageId = UUID.fromString(message.id).toString(),
                senderDeviceId = cachedDeviceId ?: error("Supabase device is not registered."),
                ciphertext = encrypted,
                nonce = crypto.nonceFromEncrypted(encrypted),
                messageVersion = 1
            )
        ).decodeAs<SupabaseMessageRow>()
    }

    override suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        setReceipt(receipt.messageId, delivered = true, read = false)

    override suspend fun sendReadReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        setReceipt(receipt.messageId, delivered = true, read = true)

    /** Resolve the UI/client message id to the server message id before writing a receipt. */
    private suspend fun setReceipt(
        messageId: String,
        delivered: Boolean,
        read: Boolean
    ): Result<Unit> = runCatching {
        val clientMessageId = UUID.fromString(messageId).toString()
        val conversationId = prepareConversationId()
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

    private suspend fun prepareConversationId(): String {
        prepareConversation()
        return cachedConversationId ?: error("Supabase conversation is not initialized.")
    }

    /**
     * Background-only Realtime stream for one local Private Chat conversation.
     *
     * This is intentionally separate from [observeEvents], which remains the
     * PostgREST polling path used by the foreground UI. Keeping the two paths
     * separate means enabling background push-like delivery cannot destabilize
     * the already-working send/receive/receipt behaviour.
     */
    fun observeRealtimeEvents(partnerId: String): Flow<ChatEvent> {
        val normalizedPartnerId = partnerId.trim()
        if (normalizedPartnerId.isBlank()) return emptyFlow()

        return channelFlow {
            val userId = sessionManager.ensureAnonymousSession().getOrThrow()
            val conversationId = ensureConversationForPartner(normalizedPartnerId)
            val channel = supabase.channel("sigma-chat-background-$conversationId")
            val clientMessageIdByServerId = mutableMapOf<String, String>()

            val messageFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "messages"
                filter("conversation_id", io.github.jan.supabase.postgrest.query.filter.FilterOperator.EQ, conversationId)
            }
            val receiptFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "message_receipts"
            }

            val messageJob = launch {
                messageFlow.collect { action ->
                    if (!isActive) return@collect
                    val row = runCatching { action.decodeRecord<SupabaseMessageRow>() }.getOrNull() ?: return@collect
                    if (row.senderUserId == userId) return@collect
                    clientMessageIdByServerId[row.id] = row.clientMessageId
                    val text = runCatching { crypto.decrypt(row.ciphertext) }.getOrNull() ?: return@collect
                    send(
                        ChatEvent.Message(
                            ChatMessage(
                                id = row.clientMessageId,
                                senderId = normalizedPartnerId,
                                text = text,
                                createdAt = parseTimestamp(row.createdAt),
                                deliveryStatus = MessageDeliveryStatus.DELIVERED
                            )
                        )
                    )
                }
            }

            val receiptJob = launch {
                receiptFlow.collect { action ->
                    if (!isActive) return@collect
                    val row = runCatching { action.decodeRecord<SupabaseReceiptRow>() }.getOrNull() ?: return@collect
                    if (row.userId == userId) return@collect
                    val clientMessageId = clientMessageIdByServerId[row.messageId]
                        ?: resolveClientMessageId(row.messageId)
                        ?: return@collect

                    when {
                        row.readAt != null -> send(
                            ChatEvent.Read(
                                ChatReceipt(
                                    messageId = clientMessageId,
                                    senderId = normalizedPartnerId,
                                    type = ChatReceiptType.READ
                                )
                            )
                        )
                        row.deliveredAt != null -> send(
                            ChatEvent.Delivered(
                                ChatReceipt(
                                    messageId = clientMessageId,
                                    senderId = normalizedPartnerId,
                                    type = ChatReceiptType.DELIVERED
                                )
                            )
                        }
                    }
                }
            }

            try {
                channel.subscribe(blockUntilSubscribed = true)
                awaitCancellation()
            } finally {
                messageJob.cancel()
                receiptJob.cancel()
                runCatching { channel.unsubscribe() }
            }
        }
    }

    /**
     * Observe a conversation using PostgREST polling.
     *
     * The flow first loads the current history, then polls for newly-created
     * messages and receipt changes every [POLL_INTERVAL_MS]. Network errors in
     * later polls are transient: the loop stays alive and retries on the next tick.
     */
    override fun observeEvents(topics: List<String>, ownSenderId: String): Flow<ChatEvent> {
        val normalized = topics.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return emptyFlow()

        val activeTopic = runCatching { identity.conversationTopic() }.getOrNull()
            ?: return emptyFlow()
        if (normalized.none { it == activeTopic }) return emptyFlow()

        return channelFlow {
            val preparedUserId = prepareConversation()
            val conversationId = cachedConversationId
                ?: error("Supabase conversation is not initialized.")
            val knownMessageIds = mutableSetOf<String>()
            val clientMessageIdByServerId = mutableMapOf<String, String>()
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
                    if (!isActive) return
                    if (row.conversationId != conversationId) return@forEach
                    lastSequence = maxOf(lastSequence, row.sequenceNumber)
                    clientMessageIdByServerId[row.id] = row.clientMessageId
                    if (!knownMessageIds.add(row.id)) return@forEach

                    val text = runCatching { crypto.decrypt(row.ciphertext) }.getOrNull()
                        ?: return@forEach

                    val senderId = if (row.senderUserId == preparedUserId) {
                        identity.myId
                    } else {
                        identity.partnerId
                    }

                    val status = if (row.senderUserId == preparedUserId) {
                        MessageDeliveryStatus.SENT
                    } else {
                        MessageDeliveryStatus.DELIVERED
                    }

                    send(
                        ChatEvent.Message(
                            ChatMessage(
                                id = row.clientMessageId,
                                senderId = senderId,
                                text = text,
                                createdAt = parseTimestamp(row.createdAt),
                                deliveryStatus = status
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
                    if (!isActive) return
                    if (row.userId == preparedUserId || row.messageId !in clientMessageIdByServerId) return

                    val clientMessageId = clientMessageIdByServerId[row.messageId] ?: return@forEach
                    when {
                        row.readAt != null -> {
                            send(
                                ChatEvent.Read(
                                    ChatReceipt(
                                        messageId = clientMessageId,
                                        senderId = identity.partnerId,
                                        type = ChatReceiptType.READ
                                    )
                                )
                            )
                        }
                        row.deliveredAt != null -> {
                            send(
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
            }

            fetchMessages(initial = true)
            fetchReceipts()

            while (isActive) {
                delay(POLL_INTERVAL_MS)
                runCatching {
                    fetchMessages(initial = false)
                    fetchReceipts()
                }.onFailure {
                    // Keep the polling loop alive across temporary network failures.
                }
            }

            awaitCancellation()
        }
    }

    private suspend fun ensureConversationForPartner(partnerId: String): String {
        return supabase.postgrest.rpc(
            "sigma_ensure_conversation",
            EnsureConversationRpcParams(
                partnerPublicId = partnerId,
                conversationKey = identity.conversationKeyFor(partnerId)
                    .joinToString("") { "%02x".format(it) }
            )
        ).decodeAs<String>()
    }

    private suspend fun resolveClientMessageId(serverMessageId: String): String? {
        return supabase.postgrest
            .from("messages")
            .select {
                filter { eq("id", serverMessageId) }
            }
            .decodeSingleOrNull<SupabaseMessageRow>()
            ?.clientMessageId
    }

    private suspend fun prepareConversation(): String = prepareMutex.withLock {
        val userId = sessionManager.ensureAnonymousSession().getOrThrow()

        if (cachedDeviceId == null) {
            cachedDeviceId = registerDeviceWithRecovery()
        }

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
    }
}
