package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val ntfyRepository: NtfyChatRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val senderId = UUID.randomUUID().toString()
    private var currentTopic: String? = null

    fun connect(topic: String) {
        val normalized = topic.trim()
        if (normalized.isBlank()) {
            _error.value = "Enter a room code first."
            return
        }
        if (currentTopic == normalized && _connected.value) return

        currentTopic = normalized
        _messages.value = emptyList()
        _error.value = null
        _connected.value = true

        viewModelScope.launch {
            chatRepository.observe(normalized, senderId)
                .collect { message ->
                    if (_messages.value.none { it.id == message.id }) {
                        _messages.value = _messages.value + message
                    }
                }
        }
    }

    fun disconnect() {
        currentTopic = null
        _connected.value = false
    }

    fun send(text: String) {
        val topic = currentTopic ?: return
        val clean = text.trim()
        if (clean.isBlank()) return

        val message = ntfyRepository.createMessage(senderId, clean)
        _messages.value = _messages.value + message
        _error.value = null

        viewModelScope.launch {
            chatRepository.send(topic, message)
                .onFailure { error ->
                    _messages.value = _messages.value.filterNot { it.id == message.id }
                    _error.value = error.message ?: "Unable to send message."
                }
        }
    }
}
