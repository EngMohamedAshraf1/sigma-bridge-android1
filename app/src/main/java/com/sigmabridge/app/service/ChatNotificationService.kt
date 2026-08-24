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
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the private-chat relay listener alive while Sigma Bridge is in the background.
 * The service only handles chat notifications; Telegram Bridge remains in its own service.
 */
@AndroidEntryPoint
class ChatNotificationService : Service() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var identity: ChatIdentity

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (identity.partnerId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.coroutineContext.cancelChildren()
        observeMessages()
        return START_STICKY
    }

    private fun observeMessages() {
        val topic = runCatching { identity.conversationTopic() }.getOrNull() ?: return
        val partnerId = identity.partnerId
        if (partnerId.isBlank()) return

        serviceScope.launch {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val storedLastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
            var lastNotifiedAt = if (storedLastNotifiedAt == 0L) {
                System.currentTimeMillis().also {
                    prefs.edit().putLong(KEY_LAST_NOTIFIED_AT, it).apply()
                }
            } else {
                storedLastNotifiedAt
            }

            chatRepository.observe(topic, identity.myId).collect { message ->
                if (message.createdAt <= lastNotifiedAt) return@collect

                if (postMessageNotification(partnerId, message.id)) {
                    lastNotifiedAt = message.createdAt
                    prefs.edit().putLong(KEY_LAST_NOTIFIED_AT, lastNotifiedAt).apply()
                }
            }
        }
    }

    private fun postMessageNotification(partnerId: String, messageId: String): Boolean {
        if (checkSelfPermissionCompat(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val openChatIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_OPEN_PRIVATE_CHAT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            messageId.hashCode(),
            openChatIntent,
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
        private const val SERVICE_CHANNEL_ID = "sigma_chat_service"
        private const val CHAT_CHANNEL_ID = "sigma_chat_messages"
        private const val SERVICE_NOTIFICATION_ID = 2001
        private const val PREFS_NAME = "sigma_bridge_chat_notifications"
        private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"

        fun startIntent(context: Context): Intent =
            Intent(context, ChatNotificationService::class.java)
    }
}