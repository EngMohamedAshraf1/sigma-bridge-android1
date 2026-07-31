package com.sigmabridge.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.model.HealthComponent
import com.sigmabridge.app.domain.model.HealthStatus
import com.sigmabridge.app.domain.model.ServiceHealth
import com.sigmabridge.app.domain.usecase.ObserveHealthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private val INITIAL_HEALTH: List<ServiceHealth> = listOf(
    ServiceHealth(HealthComponent.TELEGRAM, HealthStatus.UNKNOWN),
    ServiceHealth(HealthComponent.GEMINI, HealthStatus.UNKNOWN),
    ServiceHealth(HealthComponent.INTERNET, HealthStatus.CHECKING),
    ServiceHealth(HealthComponent.BRIDGE_SERVICE, HealthStatus.DISABLED)
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeHealthUseCase: ObserveHealthUseCase
) : ViewModel() {

    val health: StateFlow<List<ServiceHealth>> = observeHealthUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), INITIAL_HEALTH)
}
