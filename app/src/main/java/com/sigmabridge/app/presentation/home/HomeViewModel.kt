package com.sigmabridge.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.model.BridgeServiceState
import com.sigmabridge.app.domain.model.GeminiHealth
import com.sigmabridge.app.domain.model.HomeHealthState
import com.sigmabridge.app.domain.model.InternetHealth
import com.sigmabridge.app.domain.model.TelegramHealth
import com.sigmabridge.app.domain.usecase.ObserveHealthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private val INITIAL_HEALTH = HomeHealthState(
    bridge = BridgeServiceState.DISABLED,
    telegram = TelegramHealth.UNKNOWN,
    gemini = GeminiHealth.UNKNOWN,
    internet = InternetHealth.UNKNOWN
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeHealthUseCase: ObserveHealthUseCase
) : ViewModel() {

    val health: StateFlow<HomeHealthState> = observeHealthUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), INITIAL_HEALTH)
}
