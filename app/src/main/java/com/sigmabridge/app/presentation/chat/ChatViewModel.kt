package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatOutboxStore
import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.ChatTranslationService
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val ntfyRepository: NtfyChatRepository,
    private val chatTranslationService: ChatTranslationService,
    private val historyStore: ChatHistoryStore,
    private val outboxStore: ChatOutboxStore,
    private val identity: ChatIdentity
) : ViewModel() {
    val myId: String = identity.myId
    private val _partnerId = MutableStateFlow(identity.partnerId)
    val partnerId: StateFlow<String> = _partnerId.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    val ownSenderId: String = myId
    private var currentTopic: String? = null
    private var currentHistoryKey: String? = null
    private var outboxStatusJob: Job? = null

    init { if (_partnerId.value.isNotBlank()) connect() }

    fun setPartnerId(value: String) {
        val normalized = value.trim()
        identity.partnerId = normalized
        _partnerId.value = normalized
        disconnect()
        if (normalized.isNotBlank()) connect()
    }

    fun startNewChat() {
        identity.partnerId = ""
        _partnerId.value = ""
        disconnect()
        _messages.value = emptyList()
        _error.value = null
    }

    fun connect() {
        val partner = identity.partnerId
        if (partner.isBlank()) { _error.value = "Enter the partner ID first."; return }
        if (partner == myId) { _error.value = "Partner ID must be different from your own ID."; return }
        val topic = runCatching { identity.conversationTopic() }.getOrElse {
            _error.value = it.message ?: "Unable to create conversation."; return
        }
        if (currentTopic == topic && _connected.value) return
        outboxStatusJob?.cancel()
        currentTopic = topic
        currentHistoryKey = identity.conversationKey().joinToString("") { "%02x".format(it) }
        val historyKey = currentHistoryKey!!
        val pendingIds = outboxStore.load(historyKey).map { it.id }.toSet()
        _messages.value = historyStore.load(historyKey).map { message ->
            if (message.senderId == ownSenderId && message.id in pendingIds) {
                message.copy(deliveryStatus = MessageDeliveryStatus.PENDING)
            } else {
                message
            }
        }
        _error.value = null
        _connected.value = true

        viewModelScope.launch {
            runCatching {
                chatRepository.observe(topic, ownSenderId).collect { message ->
                    if (_messages.value.any { it.id == message.id }) return@collect
                    val translated = chatTranslationService.translateIncoming(message.text)
                    val visible = message.copy(text = translated.getOrElse { error ->
                        _error.value = error.message ?: "Unable to translate incoming message."
                        message.text
                    })
                    val updated = _messages.value + visible
                    _messages.value = updated
                    currentHistoryKey?.let { historyStore.save(it, updated) }
                }
            }.onFailure { error ->
                _connected.value = false
                _error.value = error.message ?: "Chat connection lost."
            }
        }

        outboxStatusJob = viewModelScope.launch {
            while (isActive) {
                val pendingIds = outboxStore.load(historyKey).map { it.id }.toSet()
                val current = _messages.value
                var changed = false
                val updated = current.map { message ->
                    if (message.senderId == ownSenderId &&
                        message.deliveryStatus == MessageDeliveryStatus.PENDING &&
                        message.id !in pendingIds
                    ) {
                        changed = true
                        message.copy(deliveryStatus = MessageDeliveryStatus.SENT)
                    } else {
                        message
                    }
                }
                if (changed) {
                    _messages.value = updated
                    historyStore.save(historyKey, updated)
                }
                delay(1_000L)
            }
        }
    }

    fun disconnect() {
        outboxStatusJob?.cancel()
        outboxStatusJob = null
        currentTopic = null
        currentHistoryKey = null
        _connected.value = false
    }

    fun send(text: String) {
        val topic = currentTopic ?: return
        val historyKey = currentHistoryKey ?: return
        val clean = text.trim()
        if (clean.isBlank()) return

        val localMessage = ntfyRepository.createMessage(
            ownSenderId,
            clean
        ).copy(deliveryStatus = MessageDeliveryStatus.PENDING)

        val updatedWithPending = _messages.value + localMessage
        _messages.value = updatedWithPending
        historyStore.save(historyKey, updatedWithPending)
        outboxStore.add(historyKey, localMessage)
        _error.value = null

        viewModelScope.launch {
            deliverPendingMessage(historyKey, topic, localMessage)
        }
    }

    private suspend fun deliverPendingMessage(
        historyKey: String,
        topic: String,
        pendingMessage: ChatMessage
    ) {
        val translated = chatTranslationService.translateOutgoing(pendingMessage.text)
        if (translated.isFailure) {
            _error.value = translated.exceptionOrNull()?.message ?: "Message queued. Translation will retry later."
            return
        }

        chatRepository.send(topic, pendingMessage.copy(text = translated.getOrThrow()))
            .onSuccess {
                outboxStore.remove(historyKey, pendingMessage.id)
                val updated = _messages.value.map {
                    if (it.id == pendingMessage.id) it.copy(deliveryStatus = MessageDeliveryStatus.SENT) else it
                }
                _messages.value = updated
                historyStore.save(historyKey, updated)
            }
            .onFailure { error ->
                _error.value = error.message ?: "Message queued. It will retry when the connection returns."
            }
    }
}
