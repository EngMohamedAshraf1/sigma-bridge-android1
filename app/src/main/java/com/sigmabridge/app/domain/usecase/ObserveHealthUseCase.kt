package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.HealthComponent
import com.sigmabridge.app.domain.model.HealthStatus
import com.sigmabridge.app.domain.model.ServiceHealth
import com.sigmabridge.app.domain.repository.ConnectivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Telegram and Gemini stay UNKNOWN placeholders until Phase 3's
 * TelegramRepository and Phase 5's TranslationRepository can report real
 * state. Internet is real starting now — this is the only row here backed
 * by an actual signal (ConnectivityRepository).
 */
class ObserveHealthUseCase @Inject constructor(
    private val connectivityRepository: ConnectivityRepository
) {
    operator fun invoke(): Flow<List<ServiceHealth>> =
        connectivityRepository.isConnected.map { connected ->
            listOf(
                ServiceHealth(HealthComponent.TELEGRAM, HealthStatus.UNKNOWN, "Not connected yet"),
                ServiceHealth(HealthComponent.GEMINI, HealthStatus.UNKNOWN, "Not checked yet"),
                ServiceHealth(
                    HealthComponent.INTERNET,
                    if (connected) HealthStatus.HEALTHY else HealthStatus.ERROR,
                    if (connected) "Connected" else "No connection"
                ),
                ServiceHealth(HealthComponent.BRIDGE_SERVICE, HealthStatus.DISABLED, "Not implemented yet (Phase 7)")
            )
        }
}
