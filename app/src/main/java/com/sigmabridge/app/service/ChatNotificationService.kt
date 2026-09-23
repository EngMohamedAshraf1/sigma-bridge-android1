package com.sigmabridge.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatCrypto
import com.sigmabridge.app.data.chat.ChatForegroundState
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatInboxRepository
import com.sigmabridge.app.data.chat.ChatNetworkState
import com.sigmabridge.app.data.chat.SupabaseSessionManager
import com.sigmabridge.app.data.chat.ChatOutboxStore
import com.sigmabridge.app.data.chat.ChatProfileRepository
import com.sigmabridge.app.data.chat.ChatUnreadStore
import com.sigmabridge.app.data.chat.SupabaseChatRepository
import com.sigmabridge.app.data.chat.SupabaseUndeliveredMessageV2Row
import com.sigmabridge.app.domain.chat.ChatConversation
import com.sigmabridge.app.domain.chat.ChatEvent
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReceipt
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.ChatTranslationService
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import com.sigmabridge.app.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatNotificationService : Service() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var supabaseChatRepository: SupabaseChatRepository
    @Inject lateinit var chatTranslationService: ChatTranslationService
    @Inject lateinit var chatProfileRepository: ChatProfileRepository
    @Inject lateinit var chatInboxRepository: ChatInboxRepository
    @Inject lateinit var chatCrypto: ChatCrypto
    @Inject lateinit var identity: ChatIdentity
    @Inject lateinit var conversationStore: ChatConversationStore
    @Inject lateinit var outboxStore: ChatOutboxStore
    @Inject lateinit var historyStore: ChatHistoryStore
    @Inject lateinit var unreadStore: ChatUnreadStore
    @Inject lateinit var networkState: ChatNetworkState
    @Inject lateinit var sessionManager: SupabaseSessionManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                serviceScope.coroutineContext.cancelChildren()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                serviceScope.coroutineContext.cancelChildren()
                registerIdentityOnStartup()
                observeUndeliveredInbox()
                retryPendingMessages()
                processTranslationJobs()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun registerIdentityOnStartup() {
        serviceScope.launch {
            while (isActive) {
                if (!networkState.isOnline()) {
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                val result = runCatching { chatProfileRepository.ensureAccountDeviceRegistered() }
                if (result.isSuccess) return@launch
                android.util.Log.e(TAG, "Private chat identity registration failed; retrying", result.exceptionOrNull())
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun observeUndeliveredInbox() {
        serviceScope.launch {
            while (isActive) {
                if (!networkState.isOnline()) {
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                // Realtime is the low-latency delivery path. This inbox query is
                // intentionally kept as a slower recovery pass for missed events.
                val result = chatInboxRepository.fetchUndeliveredMessages()
                result.onFailure { error ->
                    android.util.Log.e(TAG, "Private chat inbox recovery failed; retrying", error)
                }
                for (row in result.getOrNull().orEmpty().sortedBy { it.sequenceNumber }) {
                    if (!isActive) break
                    processUndeliveredMessage(row)
                }
                delay(INBOX_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun processUndeliveredMessage(row: SupabaseUndeliveredMessageV2Row) {
        val partnerUserId = row.senderUserId.trim()
        if (partnerUserId.isBlank()) return

        val ownUserId = sessionManager.currentUserId().orEmpty()
        if (ownUserId.isBlank() || ownUserId == partnerUserId) return

        val historyKey = row.conversationId.trim()
        if (historyKey.isBlank()) return

        val existingHistory = historyStore.load(historyKey)
        val isKnownLocally = existingHistory.any { it.id == row.clientMessageId }
        val existingConversation = conversationStore.load()
            .firstOrNull { it.conversationId == historyKey }
        val displayName = existingConversation?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: chatProfileRepository.getProfileByUserId(partnerUserId)
                .getOrNull()
                ?.displayName
                ?.takeIf { it.isNotBlank() }
            ?: partnerUserId
        val keyMaterial = supabaseChatRepository.getConversationKeyV2(historyKey).getOrNull()
            ?: return
        val decrypted = runCatching {
            chatCrypto.decryptMessageForConversationKey(
                row.ciphertext,
                keyMaterial
            )
        }.getOrNull() ?: return
        val createdAt = parseTimestamp(row.createdAt)

        if (!isKnownLocally) {
            historyStore.save(
                historyKey,
                (existingHistory + ChatMessage(
                    id = row.clientMessageId,
                    senderId = partnerUserId,
                    text = decrypted.text,
                    createdAt = createdAt,
                    deliveryStatus = MessageDeliveryStatus.DELIVERED,
                    replyToMessageId = decrypted.replyToMessageId
                )).takeLast(MAX_HISTORY_MESSAGES)
            )

            conversationStore.upsert(
                existingConversation?.copy(
                    displayName = displayName,
                    lastMessage = decrypted.text,
                    lastMessageAt = createdAt
                )
                    ?: ChatConversation(
                        conversationId = historyKey,
                        partnerId = partnerUserId,
                        displayName = displayName,
                        lastMessage = decrypted.text,
                        lastMessageAt = createdAt
                    )
            )
            unreadStore.addUnread(historyKey, row.clientMessageId)
        }

        val receiptResult = supabaseChatRepository.sendDeliveredReceiptForConversationV2(
            row.conversationId,
            ChatReceipt(messageId = row.clientMessageId, senderId = partnerUserId)
        )
        if (receiptResult.isFailure || isKnownLocally) return

        if (postMessageNotification(
                displayName,
                row.conversationId,
                row.clientMessageId,
                decrypted.text
            )
        ) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong("$KEY_LAST_NOTIFIED_AT_PREFIX$partnerUserId", createdAt)
                .apply()
        }

        serviceScope.launch {
            chatTranslationService.translateIncoming(
                decrypted.text,
                row.clientMessageId,
                partnerUserId,
                row.conversationId
            )
                .onSuccess { translated ->
                    if (translated == decrypted.text) return@onSuccess
                    val latestHistory = historyStore.load(historyKey)
                    if (latestHistory.any { it.id == row.clientMessageId }) {
                        historyStore.save(
                            historyKey,
                            latestHistory.map { message ->
                                if (message.id == row.clientMessageId) message.copy(text = translated) else message
                            }
                        )
                        val latestConversation = conversationStore.load()
                            .firstOrNull { it.conversationId == historyKey }
                        if (latestConversation != null && latestConversation.lastMessage == decrypted.text) {
                            conversationStore.upsert(latestConversation.copy(lastMessage = translated))
                        }
                    }
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Private chat background translation failed for $partnerUserId", error)
                }
        }
    }

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }

    private fun updateConversationPreview(partnerId: String, text: String, at: Long) {
        val current = conversationStore.load().firstOrNull { it.partnerId == partnerId }
        conversationStore.upsert(
            current?.copy(lastMessage = text, lastMessageAt = at)
                ?: ChatConversation(
                    partnerId = partnerId,
                    displayName = partnerId,
                    lastMessage = text,
                    lastMessageAt = at
                )
        )
    }

    private fun processTranslationJobs() {
        serviceScope.launch {
            while (isActive) {
                runCatching { chatTranslationService.processPendingRemoteTranslationJobs() }
                    .onFailure { error -> android.util.Log.e(TAG, "Private chat translation worker failed", error) }
                delay(2_000L)
            }
        }
    }

    private fun historyKeyFor(partnerId: String): String =
        identity.conversationKeyFor(partnerId).joinToString("") { "%02x".format(it) }

    private fun retryPendingMessages() {
        serviceScope.launch {
            var retryDelayMs = INITIAL_RETRY_MS
            while (isActive) {
                if (!networkState.isOnline()) {
                    delay(RECONNECT_DELAY_MS)
                    continue
                }

                val conversations = conversationStore.load()
                var deliveredAny = false

                for (conversation in conversations) {
                    if (!isActive) break
                    val partnerUserId = conversation.partnerId.trim()
                    val conversationId = conversation.conversationId.trim()
                    if (partnerUserId.isBlank() || conversationId.isBlank()) continue

                    val pending = outboxStore.load(conversationId)
                        .filter { it.deliveryStatus == MessageDeliveryStatus.PENDING }
                        .sortedBy { it.createdAt }

                    for (message in pending) {
                        if (!isActive) break
                        val result = supabaseChatRepository.sendToConversationV2(
                            conversationId = conversationId,
                            partnerUserId = partnerUserId,
                            message = message
                        )
                        if (result.isSuccess) {
                            outboxStore.remove(conversationId, message.id)
                            historyStore.markSent(conversationId, message.id)
                            deliveredAny = true
                        }
                    }
                }

                if (deliveredAny) {
                    retryDelayMs = INITIAL_RETRY_MS
                    delay(IDLE_RETRY_MS)
                } else {
                    delay(if (conversations.isEmpty()) IDLE_RETRY_MS else retryDelayMs)
                    retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_MS)
                }
            }
        }
    }

    private fun postMessageNotification(displayName: String, conversationId: String, messageId: String, messageText: String): Boolean {
        if (checkSelfPermissionCompat(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false

        val openChatIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_OPEN_PRIVATE_CHAT, true)
            putExtra(MainActivity.EXTRA_OPEN_PRIVATE_CHAT_CONVERSATION_ID, conversationId)
            putExtra(MainActivity.EXTRA_OPEN_PRIVATE_CHAT_PARTNER_ID, partnerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            messageId.hashCode(),
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val preview = messageText.replace(Regex("\\s+"), " ").trim().take(120)
        val notification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(com.sigmabridge.app.R.drawable.ic_stat_sigma_bridge)
            .setContentTitle("Sigma Bridge • $displayName")
            .setContentText(preview.ifBlank { "New message" })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(this).notify(messageId.hashCode(), notification)
            true
        }.getOrDefault(false)
    }

    private fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(com.sigmabridge.app.R.drawable.ic_stat_sigma_bridge)
            .setContentTitle("Sigma Bridge")
            .setContentText("Chat notifications are active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Chat background service",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHAT_CHANNEL_ID,
                "Chat messages",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun checkSelfPermissionCompat(permission: String): Int =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission)

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.sigmabridge.app.action.START_CHAT_NOTIFICATIONS"
        const val ACTION_STOP = "com.sigmabridge.app.action.STOP_CHAT_NOTIFICATIONS"
        private const val TAG = "ChatNotificationService"
        private const val SERVICE_CHANNEL_ID = "sigma_chat_service"
        private const val CHAT_CHANNEL_ID = "sigma_chat_messages"
        private const val SERVICE_NOTIFICATION_ID = 2001
        private const val PREFS_NAME = "sigma_bridge_chat_notifications"
        private const val KEY_LAST_NOTIFIED_AT_PREFIX = "last_notified_at_"
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val IDLE_RETRY_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val PARTNER_CHECK_INTERVAL_MS = 3_000L
        private const val INBOX_POLL_INTERVAL_MS = 5_000L
        private const val MAX_HISTORY_MESSAGES = 200

        fun startIntent(context: android.content.Context): Intent =
            Intent(context, ChatNotificationService::class.java).setAction(ACTION_START)

        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, ChatNotificationService::class.java).setAction(ACTION_STOP)
    }
}
