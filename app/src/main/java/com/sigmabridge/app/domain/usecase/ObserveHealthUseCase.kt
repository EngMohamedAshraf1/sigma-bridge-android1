package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.HomeHealthState
import com.sigmabridge.app.domain.pipeline.BridgeOrchestrator
import com.sigmabridge.app.domain.repository.ConnectivityRepository
import com.sigmabridge.app.domain.repository.ImageTranslationRepository
import com.sigmabridge.app.domain.repository.TelegramRepository
import com.sigmabridge.app.domain.repository.TranslationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Combines four independently-updating signals into one reactive snapshot:
 * bridge lifecycle (BridgeOrchestrator — the same source BridgeControlScreen
 * already observes), Telegram connection health, Gemini call health, and
 * real device connectivity. No polling loop here — this is a pure
 * combine() over four hot/cold Flows that only recomputes when one of them
 * actually changes.
 */
class ObserveHealthUseCase @Inject constructor(
    private val bridgeOrchestrator: BridgeOrchestrator,
    private val telegramRepository: TelegramRepository,
    private val translationRepository: TranslationRepository,
    private val imageTranslationRepository: ImageTranslationRepository,
    private val connectivityRepository: ConnectivityRepository
) {
    operator fun invoke(): Flow<HomeHealthState> = combine(
        bridgeOrchestrator.state,
        telegramRepository.health,
        translationRepository.health,
        imageTranslationRepository.health,
        connectivityRepository.health
    ) { bridge, telegram, gemini, imageGemini, internet ->
        val displayedGemini = if (imageGemini == com.sigmabridge.app.domain.model.GeminiHealth.BUSY) {
            com.sigmabridge.app.domain.model.GeminiHealth.BUSY
        } else {
            gemini
        }
        HomeHealthState(
            bridge = bridge,
            telegram = telegram,
            gemini = displayedGemini,
            internet = internet
        )
    }
}
