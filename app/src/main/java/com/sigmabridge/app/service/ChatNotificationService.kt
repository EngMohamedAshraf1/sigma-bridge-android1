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
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatOutboxStore
import com.sigmabridge.app.data.chat.ChatUnreadStore
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

/** Keeps the private-chat listener alive in the background and retries queued sends. */
@AndroidEntryPoint
class ChatNotificationService : Service() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var identity: ChatIdentity
    @Inject lateinit var outboxStore: ChatOutboxStore
    @Inject lateinit var historyStore: ChatHistoryStore
    @Inject lateinit var unreadStore: ChatUnreadStore
    @Inject lateinit var chatTranslationService: ChatTranslationService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.coroutineContext.cancelChildren()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (identity.partnerId.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.coroutineContext.cancelChildren()
                observeChatEvents()
                retryPendingMessages()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun observeChatEvents() {
        val topic = runCatching { identity.conversationTopic() }.getOrNull() ?: return
        val partnerId = identity.partnerId
        if (partnerId.isBlank()) return
        val historyKey = runCatching { historyKey() }.getOrNull() ?: return

        serviceScope.launch {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val storedLastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
            var lastNotifiedAt = storedLastNotifiedAt

            chatRepository.observeEvents(topic, identity.myId).collect { event ->
                when (event) {
                    is ChatEvent.Message -> {
                        if (event.message.senderId != partnerId) return@collect

                        // The message has reached this device, but it may be opened later.
                        // Persist its unread ID so opening Private Chat can emit the READ receipt
                        // even if this background service consumed the live event first.
                        unreadStore.addUnread(historyKey, event.message.id)

                        // This device received the partner's message. Send the delivery receipt
                        // back using this device's own ID, so the sender recognizes the partner.
                        chatRepository.sendDeliveredReceipt(
                            topic,
                            ChatReceipt(messageId = event.message.id, senderId = identity.myId)
                        )

                        if (event.message.createdAt <= lastNotifiedAt) return@collect
                        if (postMessageNotification(partnerId, event.message.id)) {
                            lastNotifiedAt = event.message.createdAt
                            prefs.edit().putLong(KEY_LAST_NOTIFIED_AT, lastNotifiedAt).apply()
                        }
                    }
                    is ChatEvent.Delivered -> {
                        // A delivery receipt for one of my messages must come from the partner.
                        if (event.receipt.senderId != partnerId) return@collect
                        historyStore.updateDeliveryStatus(
                            historyKey,
                            event.receipt.messageId,
                            MessageDeliveryStatus.DELIVERED
                        )
                    }
                    is ChatEvent.Read -> {
                        // A read receipt for one of my messages must come from the partner.
                        if (event.receipt.senderId != partnerId) return@collect
                        historyStore.updateDeliveryStatus(
                            historyKey,
                            event.receipt.messageId,
                            MessageDeliveryStatus.READ
                        )
                    }
                }
            }
        }
    }

    private fun historyKey(): String =
        identity.conversationKey().joinToString("") { "%02x".format(it) }

    private fun retryPendingMessages() {
        val historyKey = runCatching { historyKey() }.getOrNull() ?: return
        val topic = runCatching { identity.conversationTopic() }.getOrNull() ?: return

        serviceScope.launch {
            var retryDelayMs = INITIAL_RETRY_MS
            while (isActive) {
                val pending = outboxStore.load(historyKey)
                    .filter { it.deliveryStatus == MessageDeliveryStatus.PENDING }
                    .sortedBy { it.createdAt }

                if (pending.isEmpty()) {
                    delay(IDLE_RETRY_MS)
                    continue
                }

                var deliveredAny = false
                for (message in pending) {
                    if (!isActive) break

                    val translationResult = chatTranslationService.translateOutgoing(message.text)
                    val textToSend = translationResult.getOrElse { message.text }
                    val result = chatRepository.send(topic, message.copy(text = textToSend))

                    if (result.isSuccess) {
                        outboxStore.remove(historyKey, message.id)
                        historyStore.markSent(historyKey, message.id)
                        deliveredAny = true
                    }
                }

                if (deliveredAny) retryDelayMs = INITIAL_RETRY_MS
                else {
                    delay(retryDelayMs)
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
        private const val SERVICE_CHANNEL_ID = "sigma_chat_service"
        private const val CHAT_CHANNEL_ID = "sigma_chat_messages"
        private const val SERVICE_NOTIFICATION_ID = 2001
        private const val PREFS_NAME = "sigma_bridge_chat_notifications"
        private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val IDLE_RETRY_MS = 15_000L

        fun startIntent(context: Context): Intent = Intent(context, ChatNotificationService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, ChatNotificationService::class.java).setAction(ACTION_STOP)
    }
}
