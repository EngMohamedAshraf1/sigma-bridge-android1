package com.sigmabridge.app.presentation.bridge_control

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.model.BridgeServiceState
import com.sigmabridge.app.domain.pipeline.BridgeOrchestrator
import com.sigmabridge.app.service.BridgeForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * No longer the temporary controller — this is the real control surface now.
 * start()/stop() go through BridgeForegroundService (an Intent), not
 * BridgeOrchestrator directly, because only the Service is allowed to decide
 * when the bridge actually runs in the background. BridgeOrchestrator is
 * still injected here, but only to read its `state` — a read is not
 * "controlling" the bridge, so it doesn't need to go through the Service.
 * BridgeOrchestrator is a Hilt @Singleton, so this is the exact same
 * instance the Service is driving; the displayed state is always accurate.
 */
@HiltViewModel
class BridgeControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    bridgeOrchestrator: BridgeOrchestrator
) : ViewModel() {

    val state: StateFlow<BridgeServiceState> = bridgeOrchestrator.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BridgeServiceState.STOPPED)

    fun start() {
        ContextCompat.startForegroundService(context, BridgeForegroundService.startIntent(context))
    }

    fun stop() {
        context.startService(BridgeForegroundService.stopIntent(context))
    }
}
