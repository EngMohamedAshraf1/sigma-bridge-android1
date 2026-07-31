package com.sigmabridge.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.usecase.ObserveStoredCredentialsUseCase
import com.sigmabridge.app.domain.usecase.SaveSettingsResult
import com.sigmabridge.app.domain.usecase.SaveSettingsUseCase
import com.sigmabridge.app.domain.usecase.SettingsValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val botToken: String = "",
    val geminiApiKey: String = "",
    val isSaved: Boolean = false,
    val errors: List<SettingsValidationError> = emptyList()
)

/**
 * Talks only to the domain layer (ObserveStoredCredentialsUseCase,
 * SaveSettingsUseCase) — never to SettingsRepository directly. Validation
 * lives in SaveSettingsUseCase, not here; this class just displays whatever
 * errors that use case returns.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val observeStoredCredentials: ObserveStoredCredentialsUseCase,
    private val saveSettings: SaveSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Pre-fill fields with whatever is already stored, if anything.
        viewModelScope.launch {
            observeStoredCredentials().collect { credentials ->
                _uiState.value = _uiState.value.copy(
                    botToken = credentials.botToken.orEmpty(),
                    geminiApiKey = credentials.geminiApiKey.orEmpty()
                )
            }
        }
    }

    fun onBotTokenChanged(value: String) {
        _uiState.value = _uiState.value.copy(botToken = value, isSaved = false, errors = emptyList())
    }

    fun onGeminiApiKeyChanged(value: String) {
        _uiState.value = _uiState.value.copy(geminiApiKey = value, isSaved = false, errors = emptyList())
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            when (val result = saveSettings(state.botToken, state.geminiApiKey)) {
                is SaveSettingsResult.Success ->
                    _uiState.value = _uiState.value.copy(isSaved = true, errors = emptyList())
                is SaveSettingsResult.ValidationFailed ->
                    _uiState.value = _uiState.value.copy(isSaved = false, errors = result.errors)
            }
        }
    }
}
