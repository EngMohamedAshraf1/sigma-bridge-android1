package com.sigmabridge.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatForegroundState
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatOutboxStore
import com.sigmabridge.app.data.chat.ChatUnreadStore
import com.sigmabridge.app.data.chat.SupabaseChatRepository
import com.sigmabridge.app.domain.chat.ChatEvent
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

/** Keeps Private Chat messaging alive while the UI is closed or the screen is locked. */
@AndroidEntryPoint
class ChatNotificationService : Service() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var supabaseChatRepository: SupabaseChatRepository
    @Inject lateinit var chatTranslationService: ChatTranslationService
    @Inject lateinit var identity: ChatIdentity
    @Inject lateinit var conversationStore: ChatConversationStore
    @Inject lateinit var outboxStore: ChatOutboxStore
    @Inject lateinit var historyStore: ChatHistoryStore
    @Inject lateinit var unreadStore: ChatUnreadStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY may recreate the service with a null Intent. In that case
        // resume the Private Chat worker instead of immediately returning.
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                serviceScope.coroutineContext.cancelChildren()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (conversationStore.load().isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.coroutineContext.cancelChildren()
                observeAllChatEvents()
                retryPendingMessages()
                processTranslationJobs()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    /**
     * Background notifications use Supabase Realtime, while the foreground
     * ChatViewModel continues using its existing PostgREST polling path.
     */
    private fun observeAllChatEvents() {
        val conversations = conversationStore.load()
            .filter { it.partnerId.isNotBlank() && it.partnerId != identity.myId }
        if (conversations.isEmpty()) return

        conversations.forEach { conversation ->
            val partnerId = conversation.partnerId.trim()
            serviceScope.launch {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val lastNotifiedKey = "$KEY_LAST_NOTIFIED_AT_PREFIX$partnerId"
                var lastNotifiedAt = prefs.getLong(lastNotifiedKey, 0L)

                runCatching {
                    supabaseChatRepository.observeRealtimeEvents(partnerId).collect { event ->
                        when (event) {
                            is ChatEvent.Message -> {
                                val historyKey = historyKeyFor(partnerId)
                                val topic = identity.conversationTopicFor(partnerId)
                                val isForegroundConversation = ChatForegroundState.openPartnerId == partnerId
                                val isKnownLocally = historyStore.load(historyKey).any { it.id == event.message.id }

                                if (isForegroundConversation) {
                                    unreadStore.clear(historyKey)
                                } else if (!isKnownLocally) {
                                    unreadStore.addUnread(historyKey, event.message.id)
                                }

                                // Realtime means the message is now known to have reached this device.
                                chatRepository.sendDeliveredReceipt(
                                    topic,
                                    ChatReceipt(messageId = event.message.id, senderId = identity.myId)
                                )

                                // Never notify for a message already present in local history.
                                if (isForegroundConversation || isKnownLocally) return@collect
                                if (event.message.createdAt <= lastNotifiedAt) return@collect
                                if (postMessageNotification(partnerId, event.message.id)) {
                                    lastNotifiedAt = event.message.createdAt
                                    prefs.edit().putLong(lastNotifiedKey, lastNotifiedAt).apply()
                                }
                            }
                            is ChatEvent.Delivered -> {
                                historyStore.updateDeliveryStatus(
                                    historyKeyFor(partnerId),
                                    event.receipt.messageId,
                                    MessageDeliveryStatus.DELIVERED
                                )
                            }
                            is ChatEvent.Read -> {
                                historyStore.updateDeliveryStatus(
                                    historyKeyFor(partnerId),
                                    event.receipt.messageId,
                                    MessageDeliveryStatus.READ
                                )
                            }
                        }
                    }
                }.onFailure { error ->
                    android.util.Log.e(TAG, "Private chat realtime background observation stopped for $partnerId", error)
                }
            }
        }
    }

    /** Primary-only translation worker. Secondary devices return without Gemini credentials. */
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

    /** Retry queued outgoing messages without making Gemini a transport dependency. */
    private fun retryPendingMessages() {
        serviceScope.launch {
            var retryDelayMs = INITIAL_RETRY_MS
            while (isActive) {
                val conversations = conversationStore.load()
                var deliveredAny = false

                for (conversation in conversations) {
                    if (!isActive) break
                    val partnerId = conversation.partnerId.trim()
                    if (partnerId.isBlank() || partnerId == identity.myId) continue

                    val historyKey = runCatching { historyKeyFor(partnerId) }.getOrNull() ?: continue
                    val topic = runCatching { identity.conversationTopicFor(partnerId) }.getOrNull() ?: continue
                    val pending = outboxStore.load(historyKey)
                        .filter { it.deliveryStatus == MessageDeliveryStatus.PENDING }
                        .sortedBy { it.createdAt }

                    for (message in pending) {
                        if (!isActive) break
                        val result = chatRepository.send(topic, message)
                        if (result.isSuccess) {
                            outboxStore.remove(historyKey, message.id)
                            historyStore.markSent(historyKey, message.id)
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

    private fun postMessageNotification(partnerId: String, messageId: String): Boolean {
        if (checkSelfPermissionCompat(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false

        val openChatIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_OPEN_PRIVATE_CHAT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, messageId.hashCode(), openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Sigma Bridge")
            .setContentText("New message from $partnerId")
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Sigma Bridge")
            .setContentText("Chat notifications are active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL_ID, "Chat background service", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHAT_CHANNEL_ID, "Chat messages", NotificationManager.IMPORTANCE_DEFAULT))
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

        fun startIntent(context: Context): Intent = Intent(context, ChatNotificationService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, ChatNotificationService::class.java).setAction(ACTION_STOP)
    }
}
