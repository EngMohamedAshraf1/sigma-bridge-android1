package com.sigmabridge.app.presentation.bridge_control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.model.BridgeServiceState
import com.sigmabridge.app.domain.pipeline.BridgeOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Temporary manual control until Phase 7 replaces it with a Foreground
 * Service — there's no other way to exercise the full pipeline yet. Talks
 * only to BridgeOrchestrator, never to TelegramRepository directly.
 */
@HiltViewModel
class BridgeControlViewModel @Inject constructor(
    private val bridgeOrchestrator: BridgeOrchestrator
) : ViewModel() {

    val state: StateFlow<BridgeServiceState> = bridgeOrchestrator.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BridgeServiceState.STOPPED)

    fun start() {
        viewModelScope.launch { bridgeOrchestrator.start() }
    }

    fun stop() {
        viewModelScope.launch { bridgeOrchestrator.stop() }
    }
}
