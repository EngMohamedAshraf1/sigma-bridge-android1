package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatLanguagePreferences
import com.sigmabridge.app.data.chat.ChatOutboxStore
import com.sigmabridge.app.data.chat.ChatProfileRepository
import com.sigmabridge.app.data.chat.ChatUnreadStore
import com.sigmabridge.app.domain.chat.ChatConversation
import com.sigmabridge.app.domain.chat.ChatEvent
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReceipt
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.ChatTranslationService
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import com.sigmabridge.app.domain.model.Language
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatTranslationService: ChatTranslationService,
    private val chatLanguagePreferences: ChatLanguagePreferences,
    private val historyStore: ChatHistoryStore,
    private val outboxStore: ChatOutboxStore,
    private val unreadStore: ChatUnreadStore,
    private val conversationStore: ChatConversationStore,
    private val profileRepository: ChatProfileRepository,
    private val identity: ChatIdentity
) : ViewModel() {
    val myId: String = identity.myId
    private val _partnerId = MutableStateFlow(identity.partnerId)
    val partnerId: StateFlow<String> = _partnerId.asStateFlow()
    private val _conversationName = MutableStateFlow(identity.partnerId.ifBlank { "Private Chat" })
    val conversationName: StateFlow<String> = _conversationName.asStateFlow()
    private val _partnerAvatarPath = MutableStateFlow<String?>(null)
    val partnerAvatarPath: StateFlow<String?> = _partnerAvatarPath.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _partnerOnline = MutableStateFlow(false)
    val partnerOnline: StateFlow<Boolean> = _partnerOnline.asStateFlow()
    private val _partnerLastSeen = MutableStateFlow(0L)
    val partnerLastSeen: StateFlow<Long> = _partnerLastSeen.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _translationTargetLanguage = MutableStateFlow(chatLanguagePreferences.getTargetLanguage())
    val translationTargetLanguage: StateFlow<Language> = _translationTargetLanguage.asStateFlow()
    val ownSenderId: String = myId
    private var currentTopic: String? = null
    private var currentHistoryKey: String? = null
    private var statusSyncJob: Job? = null
    private var presenceSyncJob: Job? = null
    private val readReceiptSentIds = mutableSetOf<String>()

    init { if (_partnerId.value.isNotBlank()) connect() }

    fun setTranslationTargetLanguage(language: Language) {
        chatLanguagePreferences.setTargetLanguage(language)
        chatTranslationService.setTargetLanguage(language.code)
        _translationTargetLanguage.value = language
    }

    fun setPartnerId(value: String) {
        val normalized = value.trim()
        identity.partnerId = normalized
        _partnerId.value = normalized
        disconnect()
        if (normalized.isNotBlank()) connect()
    }

    fun connect() {
        val partner = identity.partnerId
        if (partner.isBlank()) { _error.value = "Enter the partner ID first."; return }
        if (partner == myId) { _error.value = "Partner ID must be different from your own ID."; return }
        val topic = runCatching { identity.conversationTopic() }.getOrElse {
            _error.value = sanitizeChatError(it); return
        }
        if (currentTopic == topic && _connected.value) return
        statusSyncJob?.cancel()
        presenceSyncJob?.cancel()
        readReceiptSentIds.clear()
        currentTopic = topic
        currentHistoryKey = identity.conversationKey().joinToString("") { "%02x".format(it) }
        val historyKey = currentHistoryKey!!
        val pendingIds = outboxStore.load(historyKey).map { it.id }.toSet()
        val existingConversation = conversationStore.load().firstOrNull { it.partnerId == partner }
        val history = historyStore.load(historyKey).map { message ->
            if (message.senderId == ownSenderId && message.id in pendingIds) {
                message.copy(deliveryStatus = MessageDeliveryStatus.PENDING)
            } else message
        }
        _messages.value = history
        _conversationName.value = existingConversation?.displayName ?: partner
        _partnerAvatarPath.value = existingConversation?.avatarPath
        _partnerLastSeen.value = 0L
        _partnerOnline.value = false
        conversationStore.upsert(
            ChatConversation(
                partnerId = partner,
                displayName = existingConversation?.displayName ?: partner,
                lastMessage = history.lastOrNull()?.text.orEmpty(),
                lastMessageAt = history.lastOrNull()?.createdAt ?: existingConversation?.lastMessageAt ?: 0L,
                avatarPath = existingConversation?.avatarPath
            )
        )
        _error.value = null
        _connected.value = true

        viewModelScope.launch {
            profileRepository.getProfileByPublicId(partner)
                .getOrNull()
                ?.let { profile ->
                    _partnerAvatarPath.value = profile.avatarPath
                    conversationStore.upsert(
                        conversationStore.load().firstOrNull { it.partnerId == partner }?.copy(avatarPath = profile.avatarPath)
                            ?: ChatConversation(partnerId = partner, displayName = profile.displayName, avatarPath = profile.avatarPath)
                    )
                }
        }

        presenceSyncJob = viewModelScope.launch {
            while (isActive && currentHistoryKey == historyKey) {
                profileRepository.touchMyLastSeen()
                profileRepository.getLastSeenByPublicId(partner)
                    .getOrNull()
                    ?.let { seenAt ->
                        _partnerLastSeen.value = seenAt
                        _partnerOnline.value = seenAt > 0L && System.currentTimeMillis() - seenAt <= ONLINE_WINDOW_MS
                    }
                    ?: run {
                        _partnerLastSeen.value = 0L
                        _partnerOnline.value = false
                    }
                delay(LAST_SEEN_REFRESH_MS)
            }
        }

        markVisibleMessagesRead()

        viewModelScope.launch {
            runCatching {
                chatRepository.observeEvents(topic, ownSenderId).collect { event ->
                    when (event) {
                        is ChatEvent.Message -> {
                            if (_messages.value.any { it.id == event.message.id }) return@collect

                            val translated = chatTranslationService.translateIncoming(
                                event.message.text,
                                event.message.id
                            )
                            val visible = event.message.copy(text = translated.getOrElse { error ->
                                _error.value = sanitizeChatError(error)
                                event.message.text
                            })
                            val updated = _messages.value + visible
                            _messages.value = updated
                            historyStore.save(historyKey, updated)
                            updateConversationPreview(partner, visible.text, visible.createdAt)
                            sendReadReceiptForMessage(topic, historyKey, event.message.id)
                        }
                        is ChatEvent.Delivered -> updateReceiptStatus(
                            historyKey,
                            event.receipt.messageId,
                            event.receipt.senderId,
                            MessageDeliveryStatus.DELIVERED
                        )
                        is ChatEvent.Read -> updateReceiptStatus(
                            historyKey,
                            event.receipt.messageId,
                            event.receipt.senderId,
                            MessageDeliveryStatus.READ
                        )
                    }
                }
            }.onFailure { error ->
                _connected.value = false
                _error.value = sanitizeChatError(error)
            }
        }

        statusSyncJob = viewModelScope.launch {
            while (isActive) {
                val pendingIds = outboxStore.load(historyKey).map { it.id }.toSet()
                val storedById = historyStore.load(historyKey).associateBy { it.id }
                val current = _messages.value
                var changed = false
                val updated = current.map { message ->
                    if (message.senderId != ownSenderId) return@map message
                    val stored = storedById[message.id]
                    val targetStatus = when {
                        message.id in pendingIds -> MessageDeliveryStatus.PENDING
                        stored?.deliveryStatus == MessageDeliveryStatus.READ -> MessageDeliveryStatus.READ
                        stored?.deliveryStatus == MessageDeliveryStatus.DELIVERED -> MessageDeliveryStatus.DELIVERED
                        else -> MessageDeliveryStatus.SENT
                    }
                    if (message.deliveryStatus != targetStatus) {
                        changed = true
                        message.copy(deliveryStatus = targetStatus)
                    } else message
                }
                if (changed) _messages.value = updated
                delay(1_000L)
            }
        }
    }

    fun markVisibleMessagesRead() {
        val topic = currentTopic ?: return
        val historyKey = currentHistoryKey ?: return
        unreadStore.clear(historyKey)
        val incomingIds = _messages.value
            .asSequence()
            .filter { it.senderId == identity.partnerId }
            .map { it.id }
            .toList()
        incomingIds.forEach { messageId -> sendReadReceiptForMessage(topic, historyKey, messageId) }
    }

    private fun sendReadReceiptForMessage(topic: String, historyKey: String, messageId: String) {
        if (!readReceiptSentIds.add(messageId)) return
        viewModelScope.launch {
            chatRepository.sendReadReceipt(
                topic,
                ChatReceipt(messageId = messageId, senderId = ownSenderId)
            ).onSuccess {
                unreadStore.remove(historyKey, messageId)
            }.onFailure {
                readReceiptSentIds.remove(messageId)
            }
        }
    }

    private fun updateConversationPreview(partnerId: String, lastMessage: String, lastMessageAt: Long) {
        val current = conversationStore.load().firstOrNull { it.partnerId == partnerId }
        conversationStore.upsert(
            ChatConversation(
                partnerId = partnerId,
                displayName = current?.displayName ?: partnerId,
                lastMessage = lastMessage,
                lastMessageAt = lastMessageAt,
                avatarPath = current?.avatarPath
            )
        )
    }

    private fun updateReceiptStatus(
        historyKey: String,
        messageId: String,
        receiptSenderId: String,
        status: MessageDeliveryStatus
    ) {
        if (receiptSenderId != identity.partnerId) return
        val updated = _messages.value.map { message ->
            if (message.id == messageId && message.senderId == ownSenderId && message.deliveryStatus.ordinal < status.ordinal) {
                message.copy(deliveryStatus = status)
            } else message
        }
        if (updated != _messages.value) _messages.value = updated
        historyStore.updateDeliveryStatus(historyKey, messageId, status)
    }

    fun disconnect() {
        statusSyncJob?.cancel()
        statusSyncJob = null
        presenceSyncJob?.cancel()
        presenceSyncJob = null
        currentTopic = null
        currentHistoryKey = null
        _connected.value = false
        _partnerOnline.value = false
        _partnerLastSeen.value = 0L
        readReceiptSentIds.clear()
    }

    fun send(text: String) {
        val topic = currentTopic ?: return
        val historyKey = currentHistoryKey ?: return
        val clean = text.trim()
        if (clean.isBlank()) return

        val localMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = ownSenderId,
            text = clean,
            createdAt = System.currentTimeMillis(),
            deliveryStatus = MessageDeliveryStatus.PENDING
        )

        val updatedWithPending = _messages.value + localMessage
        _messages.value = updatedWithPending
        historyStore.save(historyKey, updatedWithPending)
        outboxStore.add(historyKey, localMessage)
        updateConversationPreview(identity.partnerId, clean, localMessage.createdAt)
        _error.value = null

        viewModelScope.launch { deliverPendingMessage(historyKey, topic, localMessage) }
    }

    private suspend fun deliverPendingMessage(
        historyKey: String,
        topic: String,
        pendingMessage: ChatMessage
    ) {
        chatRepository.send(topic, pendingMessage)
            .onSuccess {
                outboxStore.remove(historyKey, pendingMessage.id)
                val persisted = historyStore.markSent(historyKey, pendingMessage.id)
                val persistedStatus = persisted.firstOrNull { it.id == pendingMessage.id }?.deliveryStatus
                    ?: MessageDeliveryStatus.SENT
                _messages.value = _messages.value.map {
                    if (it.id == pendingMessage.id) it.copy(deliveryStatus = persistedStatus) else it
                }
            }
            .onFailure { error ->
                _error.value = sanitizeChatError(error)
            }
    }

    private fun sanitizeChatError(error: Throwable): String {
        val raw = error.message.orEmpty()
        val normalized = raw.uppercase()
        return when {
            "PUBLIC_ID_ALREADY_IN_USE" in normalized ->
                "تم اكتشاف تعارض في هوية الجهاز وتمت محاولة استعادتها. أعد فتح المحادثة."
            "PARTNER_NOT_FOUND" in normalized ->
                "معرّف الطرف الآخر غير مسجل بعد على Sigma Bridge. افتح التطبيق على الجهاز الآخر وسجّل هويته أولًا."
            "AUTH_REQUIRED" in normalized ->
                "جلسة Sigma Bridge غير صالحة. أعد تشغيل التطبيق وحاول مرة أخرى."
            "INVALID_PUBLIC_ID" in normalized || "PUBLIC_ID_TOO_LONG" in normalized ->
                "تعذر تسجيل هوية Sigma Bridge لهذا الجهاز."
            "TRANSLATION_FAILED" in normalized || "REMOTE_TRANSLATION_FAILED" in normalized || "TRANSLATION" in normalized ->
                "تعذر الحصول على الترجمة حاليًا. ستظل الرسالة الأصلية متاحة."
            "SUPABASE" in normalized || "HTTP 400" in normalized || "STATUS_CODE=400" in normalized ->
                "تعذر إنشاء المحادثة مع الخادم. تحقق من اتصال الإنترنت وحاول مرة أخرى."
            raw.contains("Authorization", ignoreCase = true) || raw.contains("Bearer", ignoreCase = true) ->
                "حدث خطأ في الاتصال بالخادم."
            raw.length > 220 ->
                "حدث خطأ أثناء الاتصال بالمحادثة. حاول مرة أخرى."
            else -> raw.ifBlank { "حدث خطأ أثناء الاتصال بالمحادثة." }
        }
    }

    private companion object {
        const val LAST_SEEN_REFRESH_MS = 15_000L
        const val ONLINE_WINDOW_MS = 45_000L
    }
}
