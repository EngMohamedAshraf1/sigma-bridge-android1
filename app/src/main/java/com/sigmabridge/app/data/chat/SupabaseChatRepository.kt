package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatEvent
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReceipt
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.decodeList
import io.github.jan.supabase.postgrest.decodeSingle
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase transport for Private Chat.
 *
 * This is deliberately additive in v1: ChatModule still points to ntfy until
 * Supabase connectivity is configured and tested end-to-end.
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
        ).decodeSingle<SupabaseMessageRow>()
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
        require(messageId.isNotBlank()) { "Message ID must not be blank." }
        prepareConversation()
        supabase.postgrest.rpc(
            "sigma_set_receipt",
            SetReceiptRpcParams(
                messageId = UUID.fromString(messageId).toString(),
                delivered = delivered,
                read = read
            )
        ).decodeSingle<SupabaseReceiptRow>()
    }

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

            val channel = supabase.realtime.channel("sigma-chat-$conversationId")
            try {
                channel.subscribe()

                val messageJob = launch {
                    channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                        table = "messages"
                    }.collect { change ->
                        val row = change.decodeRecord<SupabaseMessageRow>()
                        if (row.conversationId != conversationId || row.senderUserId == preparedUserId) return@collect
                        if (!knownMessageIds.add(row.id)) return@collect

                        val text = runCatching { crypto.decrypt(row.ciphertext) }.getOrNull()
                            ?: return@collect

                        send(
                            ChatEvent.Message(
                                ChatMessage(
                                    id = row.id,
                                    senderId = identity.partnerId,
                                    text = text,
                                    createdAt = parseTimestamp(row.createdAt),
                                    deliveryStatus = MessageDeliveryStatus.DELIVERED
                                )
                            )
                        )
                    }
                }

                val receiptJob = launch {
                    channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                        table = "message_receipts"
                    }.collect { change ->
                        val row = change.decodeRecord<SupabaseReceiptRow>()
                        if (row.userId == preparedUserId || row.messageId !in knownMessageIds) return@collect

                        when {
                            row.readAt != null -> send(
                                ChatEvent.Read(
                                    ChatReceipt(
                                        messageId = row.messageId,
                                        senderId = identity.partnerId,
                                        type = com.sigmabridge.app.domain.chat.ChatReceiptType.READ
                                    )
                                )
                            )
                            row.deliveredAt != null -> send(
                                ChatEvent.Delivered(
                                    ChatReceipt(
                                        messageId = row.messageId,
                                        senderId = identity.partnerId,
                                        type = com.sigmabridge.app.domain.chat.ChatReceiptType.DELIVERED
                                    )
                                )
                            )
                        }
                    }
                }

                val initial = supabase.postgrest
                    .from("messages")
                    .select {
                        filter { eq("conversation_id", conversationId) }
                    }
                    .decodeList<SupabaseMessageRow>()
                    .sortedBy { it.sequenceNumber }

                initial.forEach { row ->
                    if (!isActive) return@forEach
                    if (row.conversationId != conversationId || !knownMessageIds.add(row.id)) return@forEach
                    val text = runCatching { crypto.decrypt(row.ciphertext) }.getOrNull()
                        ?: return@forEach
                    send(
                        ChatEvent.Message(
                            ChatMessage(
                                id = row.id,
                                senderId = if (row.senderUserId == preparedUserId) identity.myId else identity.partnerId,
                                text = text,
                                createdAt = parseTimestamp(row.createdAt),
                                deliveryStatus = if (row.senderUserId == preparedUserId) {
                                    MessageDeliveryStatus.SENT
                                } else {
                                    MessageDeliveryStatus.DELIVERED
                                }
                            )
                        )
                    )
                }

                awaitCancellation()
                messageJob.cancel()
                receiptJob.cancel()
            } finally {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private suspend fun prepareConversation(): String {
        val userId = sessionManager.ensureAnonymousSession().getOrThrow()

        if (cachedDeviceId == null) {
            val result = supabase.postgrest.rpc(
                "sigma_register_device",
                RegisterDeviceRpcParams(
                    publicId = identity.myId,
                    devicePublicId = identity.devicePublicId,
                    identityPublicKey = identity.legacyIdentityKey
                )
            ).decodeList<RegisterDeviceRpcResult>().firstOrNull()
                ?: error("Supabase device registration returned no device.")
            cachedDeviceId = result.deviceId
        }

        if (cachedConversationId == null) {
            cachedConversationId = supabase.postgrest.rpc(
                "sigma_ensure_conversation",
                EnsureConversationRpcParams(
                    partnerPublicId = identity.partnerId,
                    conversationKey = identity.conversationKeyHex()
                )
            ).decodeSingle<String>()
        }

        return userId
    }

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
}
