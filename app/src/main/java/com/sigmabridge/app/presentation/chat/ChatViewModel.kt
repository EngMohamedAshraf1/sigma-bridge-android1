package com.sigmabridge.app.presentation.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatRepository
import com.sigmabridge.app.domain.chat.ChatTranslationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val ntfyRepository: NtfyChatRepository,
    private val chatTranslationService: ChatTranslationService,
    @ApplicationContext context: Context
) : ViewModel() {

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat"
        const val KEY_ROOM_CODE = "room_code"
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val savedRoomCode: String = preferences.getString(KEY_ROOM_CODE, "").orEmpty()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val ownSenderId: String = UUID.randomUUID().toString()
    private var currentTopic: String? = null

    init {
        if (savedRoomCode.isNotBlank()) {
            connect(savedRoomCode)
        }
    }

    fun connect(topic: String) {
        val normalized = topic.trim()
        if (normalized.isBlank()) {
            _error.value = "Enter a room code first."
            return
        }
        if (currentTopic == normalized && _connected.value) return

        preferences.edit().putString(KEY_ROOM_CODE, normalized).apply()
        currentTopic = normalized
        _messages.value = emptyList()
        _error.value = null
        _connected.value = true

        viewModelScope.launch {
            chatRepository.observe(normalized, ownSenderId)
                .collect { message ->
                    if (_messages.value.any { it.id == message.id }) return@collect

                    viewModelScope.launch {
                        val translated = chatTranslationService.translateIncoming(message.text)
                        val visibleMessage = message.copy(
                            text = translated.getOrElse { error ->
                                _error.value = error.message ?: "Unable to translate incoming message."
                                message.text
                            }
                        )
                        _messages.value = _messages.value + visibleMessage
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

        val localMessage = ntfyRepository.createMessage(ownSenderId, clean)
        _messages.value = _messages.value + localMessage
        _error.value = null

        viewModelScope.launch {
            val translated = chatTranslationService.translateOutgoing(clean)
            if (translated.isFailure) {
                _error.value = translated.exceptionOrNull()?.message ?: "Unable to translate message."
                return@launch
            }

            val outboundMessage = localMessage.copy(
                text = translated.getOrThrow()
            )

            chatRepository.send(topic, outboundMessage)
                .onFailure { error ->
                    _messages.value = _messages.value.filterNot { it.id == localMessage.id }
                    _error.value = error.message ?: "Unable to send message."
                }
        }
    }
}
