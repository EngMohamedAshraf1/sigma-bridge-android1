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
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ConversationMemberUserRow(
    @SerialName("user_id") val userId: String
)

@Singleton
class SupabaseChatRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager,
    private val identity: ChatIdentity,
    private val crypto: ChatCrypto,
    private val replyStore: ChatReplyStore,
    private val conversationKeyStore: ChatConversationKeyStore
) : ChatRepository {

    private var cachedDeviceId: String? = null
    private var cachedConversationId: String? = null
    private var cachedConversationPartnerId: String? = null
    private var cachedDeviceOwnerUserId: String? = null
    private val prepareMutex = Mutex()

    override suspend fun send(topic: String, message: ChatMessage): Result<Unit> = runCatching {
        require(topic == identity.conversationTopic()) {
            "Supabase topic does not match the active partner."
        }
        val userId = prepareConversation()
        require(userId == sessionManager.currentUserId()) {
            "Supabase session changed unexpectedly."
        }
        val replyToMessageId = replyStore.getReplyTo(message.id) ?: replyStore.consumePendingReply()
        replyToMessageId?.let { replyStore.setReplyTo(message.id, it) }
        val encrypted = crypto.encryptMessage(message.text, replyToMessageId)
        supabase.postgrest.rpc(
            "sigma_send_message",
            SendMessageRpcParams(
                conversationKey = identity.conversationKeyHex(),
                clientMessageId = UUID.fromString(message.id).toString(),
                senderDeviceId = cachedDeviceId ?: error("Supabase device is not registered."),
                ciphertext = encrypted,
                nonce = crypto.nonceFromEncrypted(encrypted),
                messageVersion = if (replyToMessageId != null) 2 else 1
            )
        ).decodeAs<SupabaseMessageRow>()
    }

    /** Background send for an arbitrary stored conversation without changing identity.partnerId. */
    suspend fun sendToPartner(partnerId: String, message: ChatMessage): Result<Unit> = runCatching {
        val normalizedPartnerId = partnerId.trim()
        require(normalizedPartnerId.isNotBlank()) { "Supabase partner is not initialized." }
        val userId = sessionManager.ensureAuthenticatedSession().getOrThrow()
        prepareConversationForPartner(normalizedPartnerId)
        require(userId == sessionManager.currentUserId()) {
            "Supabase session changed unexpectedly."
        }
        val replyToMessageId = replyStore.getReplyTo(message.id)
        val encrypted = crypto.encryptWithPartnerMessage(message.text, normalizedPartnerId, replyToMessageId)
        supabase.postgrest.rpc(
            "sigma_send_message",
            SendMessageRpcParams(
                conversationKey = identity.conversationKeyFor(normalizedPartnerId)
                    .joinToString("") { "%02x".format(it) },
                clientMessageId = UUID.fromString(message.id).toString(),
                senderDeviceId = cachedDeviceId ?: error("Supabase device is not registered."),
                ciphertext = encrypted,
                nonce = crypto.nonceFromEncrypted(encrypted),
                messageVersion = if (replyToMessageId != null) 2 else 1
            )
        ).decodeAs<SupabaseMessageRow>()
    }

    override suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        setReceipt(receipt.messageId, delivered = true, read = false)

    override suspend fun sendReadReceipt(topic: String, receipt: ChatReceipt): Result<Unit> =
        setReceipt(receipt.messageId, delivered = true, read = true)

    /** Background Delivered receipt for an arbitrary conversation without changing identity.partnerId. */
    suspend fun sendDeliveredReceiptForPartner(
        partnerId: String,
        receipt: ChatReceipt
    ): Result<Unit> = setReceiptForPartner(partnerId, receipt.messageId, delivered = true, read = false)

    /** Background Read receipt for an arbitrary conversation without changing identity.partnerId. */
    suspend fun sendReadReceiptForPartner(
        partnerId: String,
        receipt: ChatReceipt
    ): Result<Unit> = setReceiptForPartner(partnerId, receipt.messageId, delivered = true, read = true)

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

    private suspend fun setReceiptForPartner(
        partnerId: String,
        messageId: String,
        delivered: Boolean,
        read: Boolean
    ): Result<Unit> = runCatching {
        val normalizedPartnerId = partnerId.trim()
        require(normalizedPartnerId.isNotBlank()) { "Supabase partner is not initialized." }
        val clientMessageId = UUID.fromString(messageId).toString()
        val conversationId = prepareConversationForPartner(normalizedPartnerId)

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
     * Background notification service observes inserts for one conversation over
     * Supabase Realtime instead of re-reading the whole conversation every 2s.
     *
     * The background inbox poll remains the reliability fallback; this stream is
     * the low-latency path.
     */
    fun observeRealtimeEvents(partnerId: String): Flow<ChatEvent> {
        val normalizedPartnerId = partnerId.trim()
        if (normalizedPartnerId.isBlank()) return emptyFlow()

        return channelFlow {
            val userId = sessionManager.ensureAuthenticatedSession().getOrThrow()
            val conversationId = ensureConversationForPartner(normalizedPartnerId)
            val channel = supabase.channel("sigma-chat-bg-$conversationId")

            try {
                val messageChanges =
                    channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                        table = "messages"
                        filter = "conversation_id=eq.$conversationId"
                    }

                val collectorJob = launch {
                    messageChanges.collect { action ->
                        if (!isActive) return@collect
                        val row = action.decodeRecordOrNull<SupabaseMessageRow>() ?: return@collect
                        if (row.conversationId != conversationId || row.senderUserId == userId) return@collect

                        val decrypted = runCatching {
                            crypto.decryptMessageForPartner(row.ciphertext, normalizedPartnerId)
                        }.getOrNull() ?: return@collect

                        decrypted.replyToMessageId?.let {
                            replyStore.setReplyTo(row.clientMessageId, it)
                        }

                        send(
                            ChatEvent.Message(
                                ChatMessage(
                                    id = row.clientMessageId,
                                    senderId = normalizedPartnerId,
                                    text = decrypted.text,
                                    createdAt = parseTimestamp(row.createdAt),
                                    deliveryStatus = MessageDeliveryStatus.DELIVERED,
                                    replyToMessageId = decrypted.replyToMessageId
                                )
                            )
                        )
                    }
                }

                channel.subscribe(blockUntilSubscribed = true)
                awaitCancellation()
                collectorJob.cancel()
            } finally {
                withContext(NonCancellable) {
                    supabase.realtime.removeChannel(channel)
                }
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

            val partnerUserId = resolvePartnerUserId(
                conversationId = conversationId,
                ownUserId = preparedUserId
            )

            val knownMessageIds = mutableSetOf<String>()
            val serverMessageIdToClientId = mutableMapOf<String, String>()
            var lastSequence = 0L
            val realtimeStateMutex = Mutex()

            suspend fun emitMessageRow(row: SupabaseMessageRow) {
                if (!isActive || row.conversationId != conversationId) return

                val shouldEmit = realtimeStateMutex.withLock {
                    lastSequence = maxOf(lastSequence, row.sequenceNumber)
                    serverMessageIdToClientId[row.id] = row.clientMessageId
                    knownMessageIds.add(row.id)
                }

                if (!shouldEmit || !isActive) return

                val decrypted = runCatching { crypto.decryptMessage(row.ciphertext) }.getOrNull() ?: return
                decrypted.replyToMessageId?.let { replyStore.setReplyTo(row.clientMessageId, it) }

                val senderId = if (row.senderUserId == preparedUserId) identity.myId else identity.partnerId
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
                            text = decrypted.text,
                            createdAt = parseTimestamp(row.createdAt),
                            deliveryStatus = status,
                            replyToMessageId = decrypted.replyToMessageId
                        )
                    )
                )
            }

            suspend fun fetchMessages(initial: Boolean) {
                val sequenceCutoff = if (initial) {
                    0L
                } else {
                    realtimeStateMutex.withLock { lastSequence }
                }

                val rows = supabase.postgrest.from("messages").select {
                    filter {
                        eq("conversation_id", conversationId)
                        if (!initial) gt("sequence_number", sequenceCutoff)
                    }
                }.decodeList<SupabaseMessageRow>().sortedBy { it.sequenceNumber }

                rows.forEach { row -> emitMessageRow(row) }
            }

            suspend fun emitReceiptRow(row: SupabaseReceiptRow) {
                if (!isActive || row.userId == preparedUserId) return

                val knownClientMessageId = realtimeStateMutex.withLock {
                    serverMessageIdToClientId[row.messageId]
                }

                val clientMessageId = knownClientMessageId
                    ?: supabase.postgrest
                        .from("messages")
                        .select {
                            filter {
                                eq("id", row.messageId)
                                eq("conversation_id", conversationId)
                            }
                        }
                        .decodeSingleOrNull<SupabaseMessageRow>()
                        ?.let { message ->
                            realtimeStateMutex.withLock {
                                serverMessageIdToClientId[message.id] = message.clientMessageId
                            }
                            message.clientMessageId
                        }
                    ?: return

                if (row.readAt != null) {
                    send(
                        ChatEvent.Read(
                            ChatReceipt(
                                messageId = clientMessageId,
                                senderId = identity.partnerId,
                                type = ChatReceiptType.READ
                            )
                        )
                    )
                } else if (row.deliveredAt != null) {
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

            suspend fun fetchReceipts() {
                val rows = supabase.postgrest
                    .from("message_receipts")
                    .select {
                        filter {
                            eq("user_id", partnerUserId)
                        }
                    }
                    .decodeList<SupabaseReceiptRow>()

                rows.forEach { row ->
                    val knownMessageId = realtimeStateMutex.withLock {
                        serverMessageIdToClientId[row.messageId]
                    }
                    if (knownMessageId != null) {
                        emitReceiptRow(row)
                    }
                }
            }

            val channel = supabase.channel("sigma-chat-$conversationId")
            try {
                val messageChanges =
                    channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                        table = "messages"
                        filter = "conversation_id=eq.$conversationId"
                    }

                val receiptChanges =
                    channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "message_receipts"
                        filter = "user_id=eq.$partnerUserId"
                    }

                launch {
                    messageChanges.collect { action ->
                        runCatching {
                            val row = action.decodeRecordOrNull<SupabaseMessageRow>() ?: return@runCatching
                            emitMessageRow(row)
                        }.onFailure { error ->
                            android.util.Log.e(
                                "SupabaseChatRepository",
                                "Private chat realtime message decode failed",
                                error
                            )
                        }
                    }
                }

                launch {
                    receiptChanges.collect { action ->
                        runCatching {
                            val row = (action as? HasRecord)
                                ?.decodeRecordOrNull<SupabaseReceiptRow>()
                                ?: return@runCatching
                            emitReceiptRow(row)
                        }.onFailure { error ->
                            android.util.Log.e(
                                "SupabaseChatRepository",
                                "Private chat realtime receipt decode failed",
                                error
                            )
                        }
                    }
                }

                channel.subscribe(blockUntilSubscribed = true)

                // Subscribe first, then hydrate once, so inserts that race with the
                // initial history read are buffered by Realtime instead of missed.
                fetchMessages(initial = true)
                fetchReceipts()

                // Realtime is the live path. A slow reconciliation pass is kept only
                // as recovery for any event missed during reconnects or process pauses.
                launch {
                    while (isActive) {
                        delay(RECONCILIATION_INTERVAL_MS)
                        runCatching {
                            fetchMessages(initial = false)
                            fetchReceipts()
                        }.onFailure { error ->
                            android.util.Log.e(
                                "SupabaseChatRepository",
                                "Private chat realtime reconciliation failed",
                                error
                            )
                        }
                    }
                }

                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    supabase.realtime.removeChannel(channel)
                }
            }
        }
    }

    private suspend fun resolvePartnerUserId(
        conversationId: String,
        ownUserId: String
    ): String {
        return supabase.postgrest
            .from("conversation_members")
            .select {
                filter {
                    eq("conversation_id", conversationId)
                    neq("user_id", ownUserId)
                }
            }
            .decodeList<ConversationMemberUserRow>()
            .firstOrNull()
            ?.userId
            ?: error("Supabase conversation partner is not available.")
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

    /** Prepares device auth for an arbitrary partner without changing the active conversation cache. */
    private suspend fun prepareConversationForPartner(partnerId: String): String = prepareMutex.withLock {
        sessionManager.ensureAuthenticatedSession().getOrThrow()
        if (cachedDeviceId == null) cachedDeviceId = registerDeviceWithRecovery()
        ensureConversationForPartner(partnerId)
    }

    private suspend fun prepareConversation(): String = prepareMutex.withLock {
        val userId = sessionManager.ensureAuthenticatedSession().getOrThrow()
        if (cachedDeviceId == null) cachedDeviceId = registerDeviceWithRecovery()

        val partner = identity.partnerId
        if (partner.isBlank()) error("Supabase partner is not initialized.")

        if (cachedConversationId == null || cachedConversationPartnerId != partner) {
            cachedConversationId = supabase.postgrest.rpc(
                "sigma_ensure_conversation",
                EnsureConversationRpcParams(
                    partnerPublicId = partner,
                    conversationKey = identity.conversationKeyHex()
                )
            ).decodeAs<String>()
            cachedConversationPartnerId = partner
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
                        identityPublicKey = identity.deviceIdentityKey
                    )
                ).decodeList<RegisterDeviceRpcResult>().firstOrNull()
                    ?: error("Supabase device registration returned no device.")

                identity.syncMyId(result.publicId)
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
        error("Supabase devic    /** Returns a recoverable random conversation key for an authenticated member. */
    suspend fun getConversationKeyV2(conversationId: String): Result<String> = runCatching {
        val normalized = UUID.fromString(conversationId.trim()).toString()
        conversationKeyStore.get(normalized)?.let { return@runCatching it }

        sessionManager.ensureAuthenticatedSession().getOrThrow()
        val keyMaterial = supabase.postgrest.rpc(
            "sigma_get_or_create_conversation_key_v2",
            GetConversationKeyV2RpcParams(normalized)
        ).decodeAs<String>()

        require(keyMaterial.matches(Regex("[0-9a-fA-F]{64}"))) {
            "INVALID_CONVERSATION_KEY"
        }
        conversationKeyStore.put(normalized, keyMaterial)
        keyMaterial
    }

    /** Account-identity v2: the conversation UUID is the transport identity. */
    suspend fun ensureConversationWithUserV2(partnerUserId: String): Result<String> = runCatching {
        val ownUserId = sessionManager.ensureAuthenticatedSession().getOrThrow()
            .user?.id ?: error("AUTH_REQUIRED")
        require(partnerUserId.trim().isNotBlank()) { "PARTNER_REQUIRED" }
        require(partnerUserId.trim() != ownUserId) { "PARTNER_MUST_BE_DIFFERENT" }
        ensureAccountDeviceV2(ownUserId)
        val conversationId = supabase.postgrest.rpc(
            "sigma_ensure_conversation_v2",
            EnsureConversationV2RpcParams(partnerUserId.trim())
        ).decodeAs<String>()
        getConversationKeyV2(conversationId).getOrThrow()
        conversationId
    }

    suspend fun sendToConversationV2(
        conversationId: String,
        partnerUserId: String,
        message: ChatMessage
    ): Result<Unit> = runCatching {
        val ownUserId = sessionManager.ensureAuthenticatedSession().getOrThrow()
            .user?.id ?: error("AUTH_REQUIRED")
        val normalizedConversationId = UUID.fromString(conversationId.trim()).toString()
        val normalizedPartnerId = UUID.fromString(partnerUserId.trim()).toString()
        require(normalizedPartnerId != ownUserId) { "PARTNER_MUST_BE_DIFFERENT" }
        ensureAccountDeviceV2(ownUserId)

        val replyToMessageId = replyStore.getReplyTo(message.id) ?: replyStore.consumePendingReply()
        replyToMessageId?.let { replyStore.setReplyTo(message.id, it) }
        val keyMaterial = getConversationKeyV2(normalizedConversationId).getOrThrow()
        val encrypted = crypto.encryptMessageForConversationKey(
            text = message.text,
            keyMaterial = keyMaterial,
            replyToMessageId = replyToMessageId
        )

        supabase.postgrest.rpc(
            "sigma_send_message_v2",
            SendMessageV2RpcParams(
                conversationId = normalizedConversationId,
                clientMessageId = UUID.fromString(message.id).toString(),
                senderDeviceId = cachedDeviceId ?: error("Supabase device is not registered."),
                ciphertext = encrypted,
                nonce = crypto.nonceFromEncrypted(encrypted),
                messageVersion = if (replyToMessageId != null) 2 else 1
            )
        ).decodeAs<SupabaseMessageRow>()
    }

    suspend fun sendDeliveredReceiptForConversationV2(
        conversationId: String,
        receipt: ChatReceipt
    ): Result<Unit> = setReceiptForConversationV2(conversationId, receipt.messageId, delivered = true, read = false)

    suspend fun sendReadReceiptForConversationV2(
        conversationId: String,
        receipt: ChatReceipt
    ): Result<Unit> = setReceiptForConversationV2(conversationId, receipt.messageId, delivered = true, read = true)

    private suspend fun setReceiptForConversationV2(
        conversationId: String,
        clientMessageId: String,
        delivered: Boolean,
        read: Boolean
    ): Result<Unit> = runCatching {
        val ownUserId = sessionManager.ensureAuthenticatedSession().getOrThrow()
            .user?.id ?: error("AUTH_REQUIRED")
        ensureAccountDeviceV2(ownUserId)
        val normalizedConversationId = UUID.fromString(conversationId.trim()).toString()
        val normalizedClientMessageId = UUID.fromString(clientMessageId.trim()).toString()
        val serverMessageId = supabase.postgrest
            .from("messages")
            .select {
                filter {
                    eq("conversation_id", normalizedConversationId)
                    eq("client_message_id", normalizedClientMessageId)
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

    /** Realtime observation for one account-level conversation. */
    fun observeConversationV2(
        conversationId: String,
        partnerUserId: String,
        ownUserId: String
    ): Flow<ChatEvent> {
        val normalizedConversationId = runCatching { UUID.fromString(conversationId.trim()).toString() }.getOrNull()
            ?: return emptyFlow()
        val normalizedPartnerId = runCatching { UUID.fromString(partnerUserId.trim()).toString() }.getOrNull()
            ?: return emptyFlow()
        val normalizedOwnUserId = runCatching { UUID.fromString(ownUserId.trim()).toString() }.getOrNull()
            ?: return emptyFlow()

        return channelFlow {
            ensureAccountDeviceV2(normalizedOwnUserId)
            val keyMaterial = getConversationKeyV2(normalizedConversationId).getOrThrow()
            val knownMessageIds = mutableSetOf<String>()
            val serverMessageIdToClientId = mutableMapOf<String, String>()
            var lastSequence = 0L
            val stateMutex = Mutex()

            suspend fun emitMessageRow(row: SupabaseMessageRow) {
                if (!isActive || row.conversationId != normalizedConversationId) return

                val shouldEmit = stateMutex.withLock {
                    lastSequence = maxOf(lastSequence, row.sequenceNumber)
                    serverMessageIdToClientId[row.id] = row.clientMessageId
                    knownMessageIds.add(row.id)
                }
                if (!shouldEmit || !isActive || !row.ciphertext.startsWith("sb3:")) return

                val decrypted = runCatching {
                    crypto.decryptMessageForConversationKey(
                        row.ciphertext,
                        keyMaterial
                    )
                }.getOrNull() ?: return

                decrypted.replyToMessageId?.let { replyStore.setReplyTo(row.clientMessageId, it) }
                val isMine = row.senderUserId == normalizedOwnUserId
                send(
                    ChatEvent.Message(
                        ChatMessage(
                            id = row.clientMessageId,
                            senderId = row.senderUserId,
                            text = decrypted.text,
                            createdAt = parseTimestamp(row.createdAt),
                            deliveryStatus = if (isMine) MessageDeliveryStatus.SENT else MessageDeliveryStatus.DELIVERED,
                            replyToMessageId = decrypted.replyToMessageId
                        )
                    )
                )
            }

            suspend fun fetchMessages(initial: Boolean) {
                val cutoff = if (initial) 0L else stateMutex.withLock { lastSequence }
                val rows = supabase.postgrest.from("messages").select {
                    filter {
                        eq("conversation_id", normalizedConversationId)
                        if (!initial) gt("sequence_number", cutoff)
                    }
                }.decodeList<SupabaseMessageRow>().sortedBy { it.sequenceNumber }
                rows.forEach(::emitMessageRow)
            }

            suspend fun emitReceiptRow(row: SupabaseReceiptRow) {
                if (!isActive || row.userId != normalizedPartnerId) return
                val clientMessageId = stateMutex.withLock { serverMessageIdToClientId[row.messageId] }
                    ?: supabase.postgrest.from("messages").select {
                        filter {
                            eq("id", row.messageId)
                            eq("conversation_id", normalizedConversationId)
                        }
                    }.decodeSingleOrNull<SupabaseMessageRow>()?.also { message ->
                        stateMutex.withLock {
                            serverMessageIdToClientId[message.id] = message.clientMessageId
                        }
                    }?.clientMessageId
                    ?: return

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
                    )
                }
            }

            suspend fun fetchReceipts() {
                val rows = supabase.postgrest.from("message_receipts").select {
                    filter { eq("user_id", normalizedPartnerId) }
                }.decodeList<SupabaseReceiptRow>()
                rows.forEach { row ->
                    val known = stateMutex.withLock { serverMessageIdToClientId[row.messageId] }
                    if (known != null) emitReceiptRow(row)
                }
            }

            val channel = supabase.channel("sigma-chat-v2-$normalizedConversationId")
            try {
                val messageChanges = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "messages"
                    filter = "conversation_id=eq.$normalizedConversationId"
                }
                val receiptChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "message_receipts"
                    filter = "user_id=eq.$normalizedPartnerId"
                }

                launch {
                    messageChanges.collect { action ->
                        runCatching { action.decodeRecordOrNull<SupabaseMessageRow>()?.let(::emitMessageRow) }
                            .onFailure { error -> android.util.Log.e("SupabaseChatRepository", "Private chat v2 message decode failed", error) }
                    }
                }
                launch {
                    receiptChanges.collect { action ->
                        runCatching {
                            (action as? HasRecord)?.decodeRecordOrNull<SupabaseReceiptRow>()?.let(::emitReceiptRow)
                        }.onFailure { error -> android.util.Log.e("SupabaseChatRepository", "Private chat v2 receipt decode failed", error) }
                    }
                }

                channel.subscribe(blockUntilSubscribed = true)
                fetchMessages(initial = true)
                fetchReceipts()

                launch {
                    while (isActive) {
                        delay(RECONCILIATION_INTERVAL_MS)
                        runCatching {
                            fetchMessages(initial = false)
                            fetchReceipts()
                        }.onFailure { error -> android.util.Log.e("SupabaseChatRepository", "Private chat v2 reconciliation failed", error) }
                    }
                }
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private suspend fun ensureAccountDeviceV2(ownUserId: String): String {
        prepareMutex.withLock {
            if (cachedDeviceId != null && cachedDeviceOwnerUserId == ownUserId) {
                return@withLock cachedDeviceId!!
            }
            val result = supabase.postgrest.rpc(
                "sigma_register_account_device_v2",
                RegisterAccountDeviceRpcParams(
                    devicePublicId = identity.devicePublicId,
                    identityPublicKey = identity.deviceIdentityKey
                )
            ).decodeList<RegisterAccountDeviceRpcResult>().firstOrNull()
                ?: error("Supabase account device registration returned no device.")
            cachedDeviceId = result.deviceId
            cachedDeviceOwnerUserId = result.userId
            identity.syncDeviceRole(result.deviceRole)
            return@withLock result.deviceId
        }
    }

    suspend fun sendDeliveredReceiptForPartnerV2(
        partnerUserId: String,
        receipt: ChatReceipt
    ): Result<Unit> = runCatching {
        val conversationId = ensureConversationWithUserV2(partnerUserId).getOrThrow()
        sendDeliveredReceiptForConversationV2(conversationId, receipt).getOrThrow()
    }

    suspend fun sendReadReceiptForPartnerV2(
        partnerUserId: String,
        receipt: ChatReceipt
    ): Result<Unit> = runCatching {
        val conversationId = ensureConversationWithUserV2(partnerUserId).getOrThrow()
        sendReadReceiptForConversationV2(conversationId, receipt).getOrThrow()
    }

e registration failed after identity recovery.")
    }

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }

    private companion object {
        const val RECONCILIATION_INTERVAL_MS = 15_000L
    }
}
