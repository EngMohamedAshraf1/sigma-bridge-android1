package com.sigmabridge.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val botToken: String = "",
    val geminiApiKey: String = "",
    val isSaved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Pre-fill fields with whatever is already stored, if anything.
        viewModelScope.launch {
            settingsRepository.botToken.collect { token ->
                _uiState.value = _uiState.value.copy(botToken = token.orEmpty())
            }
        }
        viewModelScope.launch {
            settingsRepository.geminiApiKey.collect { key ->
                _uiState.value = _uiState.value.copy(geminiApiKey = key.orEmpty())
            }
        }
    }

    fun onBotTokenChanged(value: String) {
        _uiState.value = _uiState.value.copy(botToken = value, isSaved = false)
    }

    fun onGeminiApiKeyChanged(value: String) {
        _uiState.value = _uiState.value.copy(geminiApiKey = value, isSaved = false)
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.saveBotToken(state.botToken.trim())
            settingsRepository.saveGeminiApiKey(state.geminiApiKey.trim())
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }
}
