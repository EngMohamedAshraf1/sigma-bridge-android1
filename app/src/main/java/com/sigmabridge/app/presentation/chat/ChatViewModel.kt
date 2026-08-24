package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.ChatTranslationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val ntfyRepository: NtfyChatRepository,
    private val chatTranslationService: ChatTranslationService,
    private val historyStore: ChatHistoryStore,
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

    init { if (_partnerId.value.isNotBlank()) connect() }

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
            _error.value = it.message ?: "Unable to create conversation."; return
        }
        if (currentTopic == topic && _connected.value) return
        currentTopic = topic
        currentHistoryKey = identity.conversationKey().joinToString("") { "%02x".format(it) }
        _messages.value = historyStore.load(currentHistoryKey!!)
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
    }

    fun disconnect() {
        currentTopic = null
        currentHistoryKey = null
        _connected.value = false
    }

    fun send(text: String) {
        val topic = currentTopic ?: return
        val clean = text.trim()
        if (clean.isBlank()) return
        val localMessage = ntfyRepository.createMessage(ownSenderId, clean)
        _messages.value = _messages.value + localMessage
        _error.value = null

        viewModelScope.launch {
            val translated = chatTranslationService.translateOutgoing(clean)
            if (translated.isFailure) {
                _messages.value = _messages.value.filterNot { it.id == localMessage.id }
                _error.value = translated.exceptionOrNull()?.message ?: "Unable to translate message."
                return@launch
            }
            chatRepository.send(topic, localMessage.copy(text = translated.getOrThrow()))
                .onSuccess { currentHistoryKey?.let { historyStore.save(it, _messages.value) } }
                .onFailure { error ->
                    _messages.value = _messages.value.filterNot { it.id == localMessage.id }
                    _error.value = error.message ?: "Unable to send message."
                }
        }
    }
}
