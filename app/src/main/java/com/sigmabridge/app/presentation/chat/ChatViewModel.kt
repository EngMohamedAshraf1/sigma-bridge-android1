package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatProfileRepository
import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatProfile
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
    private val identity: ChatIdentity,
    private val profileRepository: ChatProfileRepository
) : ViewModel() {
    val myUsername: String get() = identity.username
    val myUserId: String get() = identity.userId
    val partner: ChatProfile? get() = identity.partner

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _searchResult = MutableStateFlow<ChatProfile?>(null)
    val searchResult: StateFlow<ChatProfile?> = _searchResult.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    val ownSenderId: String get() = identity.userId
    private var currentTopic: String? = null
    private var currentHistoryKey: String? = null

    init {
        viewModelScope.launch { profileRepository.publish(identity.myProfile()) }
        if (identity.partner != null) connect()
    }

    fun saveUsername(value: String) {
        runCatching { identity.updateUsername(value) }
            .onSuccess {
                _error.value = null
                viewModelScope.launch { profileRepository.publish(identity.myProfile()) }
            }
            .onFailure { _error.value = it.message ?: "Invalid username." }
    }

    fun search(username: String) {
        viewModelScope.launch {
            _searching.value = true
            _error.value = null
            val result = profileRepository.find(username)
            _searchResult.value = result.getOrNull()
            if (result.isFailure) _error.value = result.exceptionOrNull()?.message
            if (_searchResult.value == null && _error.value == null) _error.value = "User not found."
            _searching.value = false
        }
    }

    fun startChat(profile: ChatProfile) {
        runCatching { identity.updatePartner(profile) }
            .onSuccess {
                _searchResult.value = null
                disconnect()
                connect()
            }
            .onFailure { _error.value = it.message ?: "Unable to start chat." }
    }

    fun connect() {
        val profile = identity.partner ?: return
        val topic = runCatching { identity.conversationTopic() }.getOrElse {
            _error.value = it.message ?: "Unable to create conversation."; return
        }
        if (currentTopic == topic && _connected.value) return
        currentTopic = topic
        currentHistoryKey = topic
        _messages.value = historyStore.load(topic)
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
