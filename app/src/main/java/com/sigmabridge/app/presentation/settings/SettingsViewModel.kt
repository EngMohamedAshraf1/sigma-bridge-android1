package com.sigmabridge.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.model.GeminiKeyStatus
import com.sigmabridge.app.domain.usecase.ObserveGeminiKeyStatusesUseCase
import com.sigmabridge.app.domain.usecase.ObserveStoredCredentialsUseCase
import com.sigmabridge.app.domain.usecase.SaveSettingsResult
import com.sigmabridge.app.domain.usecase.SaveSettingsUseCase
import com.sigmabridge.app.domain.usecase.SettingsValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * [id] is a client-side-only identifier (stable across edits/reordering for
 * Compose keying) - it is never persisted and has nothing to do with the
 * key's own value. [status] is null until this exact key value has been
 * observed with a real status from GeminiApiKeyManager - an in-progress
 * edit that hasn't been saved yet has no live status, and this app never
 * fabricates one.
 */
data class GeminiKeySlotUi(
    val id: String,
    val value: String,
    val status: GeminiKeyStatus?
)

data class GeminiKeySummary(
    val total: Int = 0,
    val active: Int = 0,
    val ready: Int = 0,
    val quotaExceeded: Int = 0,
    val invalid: Int = 0
)

data class SettingsUiState(
    val botToken: String = "",
    val geminiKeySlots: List<GeminiKeySlotUi> = listOf(GeminiKeySlotUi(id = "initial", value = "", status = null)),
    val geminiKeySummary: GeminiKeySummary = GeminiKeySummary(),
    val isSaved: Boolean = false,
    val errors: List<SettingsValidationError> = emptyList()
)

/**
 * Talks only to the domain layer (ObserveStoredCredentialsUseCase,
 * ObserveGeminiKeyStatusesUseCase, SaveSettingsUseCase) - never to
 * SettingsRepository or GeminiApiKeyManager directly. Validation lives in
 * SaveSettingsUseCase, not here; this class just displays whatever errors
 * that use case returns.
 *
 * Two independent collectors, deliberately not merged: the persisted key
 * list is only used to populate the *initial* set of editable slots (once
 * - otherwise typing would fight with re-emissions of what was just typed),
 * while the live status stream continuously updates just the status field
 * of whichever slots currently match a known key value.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val observeStoredCredentials: ObserveStoredCredentialsUseCase,
    private val observeGeminiKeyStatuses: ObserveGeminiKeyStatusesUseCase,
    private val saveSettings: SaveSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var hasLoadedInitialCredentials = false

    init {
        viewModelScope.launch {
            observeStoredCredentials().collect { credentials ->
                if (hasLoadedInitialCredentials) return@collect
                hasLoadedInitialCredentials = true

                val initialSlots = if (credentials.geminiApiKeys.isEmpty()) {
                    listOf(newSlot())
                } else {
                    credentials.geminiApiKeys.map { newSlot(value = it) }
                }
                _uiState.value = _uiState.value.copy(
                    botToken = credentials.botToken.orEmpty(),
                    geminiKeySlots = initialSlots
                )
            }
        }

        viewModelScope.launch {
            observeGeminiKeyStatuses().collect { keyInfos ->
                val statusByKey = keyInfos.associate { it.key to it.status }
                _uiState.value = _uiState.value.copy(
                    geminiKeySlots = _uiState.value.geminiKeySlots.map { slot ->
                        slot.copy(status = statusByKey[slot.value])
                    },
                    geminiKeySummary = GeminiKeySummary(
                        total = keyInfos.size,
                        active = keyInfos.count { it.status == GeminiKeyStatus.ACTIVE },
                        ready = keyInfos.count { it.status == GeminiKeyStatus.READY },
                        quotaExceeded = keyInfos.count { it.status == GeminiKeyStatus.QUOTA_EXCEEDED },
                        invalid = keyInfos.count { it.status == GeminiKeyStatus.INVALID }
                    )
                )
            }
        }
    }

    private fun newSlot(value: String = ""): GeminiKeySlotUi =
        GeminiKeySlotUi(id = UUID.randomUUID().toString(), value = value, status = null)

    fun onBotTokenChanged(value: String) {
        _uiState.value = _uiState.value.copy(botToken = value, isSaved = false, errors = emptyList())
    }

    fun onKeySlotChanged(id: String, value: String) {
        _uiState.value = _uiState.value.copy(
            isSaved = false,
            errors = emptyList(),
            geminiKeySlots = _uiState.value.geminiKeySlots.map { slot ->
                if (slot.id == id) slot.copy(value = value, status = null) else slot
            }
        )
    }

    fun onAddKeySlot() {
        _uiState.value = _uiState.value.copy(
            geminiKeySlots = _uiState.value.geminiKeySlots + newSlot(),
            isSaved = false
        )
    }

    fun onDeleteKeySlot(id: String) {
        val remaining = _uiState.value.geminiKeySlots.filter { it.id != id }
        _uiState.value = _uiState.value.copy(
            geminiKeySlots = remaining.ifEmpty { listOf(newSlot()) },
            isSaved = false,
            errors = emptyList()
        )
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            val keys = state.geminiKeySlots.map { it.value }
            when (val result = saveSettings(state.botToken, keys)) {
                is SaveSettingsResult.Success ->
                    _uiState.value = _uiState.value.copy(isSaved = true, errors = emptyList())
                is SaveSettingsResult.ValidationFailed ->
                    _uiState.value = _uiState.value.copy(isSaved = false, errors = result.errors)
            }
        }
    }
}
