package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatAlternativeTranslationRepository
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatTranslationStatus
import com.sigmabridge.app.domain.model.Language
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-scoped manual alternative translation for Private Chat.
 * The request is handled by Supabase -> Google Gemini, so the primary phone
 * does not need to be online.
 */
@HiltViewModel
class ChatAlternativeTranslationViewModel @Inject constructor(
    private val repository: ChatAlternativeTranslationRepository,
    private val historyStore: ChatHistoryStore,
    private val identity: ChatIdentity
) : ViewModel() {
    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

    private val _loadingIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingIds: StateFlow<Set<String>> = _loadingIds.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun translate(message: ChatMessage, target: Language) {
        if (_loadingIds.value.contains(message.id)) return
        if (target.code == "auto") return

        val text = message.originalText.ifBlank { message.text }.trim()
        if (text.isBlank()) return

        val historyKey = runCatching {
            identity.conversationKey().joinToString("") { "%02x".format(it) }
        }.getOrElse {
            _error.value = sanitizeError(it)
            return
        }

        _loadingIds.value = _loadingIds.value + message.id
        _error.value = null

        viewModelScope.launch {
            val result = repository.translate(text, target.code)
            result.fold(
                onSuccess = { translated ->
                    _translations.value = _translations.value + (message.id to translated)

                    val stored = historyStore.load(historyKey)
                    historyStore.save(
                        historyKey,
                        stored.map { current ->
                            if (current.id == message.id) {
                                current.copy(
                                    text = translated,
                                    originalText = current.originalText.ifBlank { text },
                                    translatedText = translated,
                                    translationStatus = ChatTranslationStatus.COMPLETED,
                                    translatedToLanguage = target.code
                                )
                            } else {
                                current
                            }
                        }
                    )
                },
                onFailure = { error ->
                    _error.value = sanitizeError(error)
                }
            )

            _loadingIds.value = _loadingIds.value - message.id
        }
    }

    fun isLoading(messageId: String): Boolean = messageId in _loadingIds.value

    private fun sanitizeError(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }?.take(180) ?: "Alternative translation failed."
}
