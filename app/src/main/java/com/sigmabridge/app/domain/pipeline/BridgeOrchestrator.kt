package com.sigmabridge.app.domain.pipeline

import com.sigmabridge.app.domain.dispatch.UpdateDispatcher
import com.sigmabridge.app.domain.model.BridgeServiceState
import com.sigmabridge.app.domain.repository.TelegramRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TelegramRepository only starts/stops the polling loop and emits raw
 * updates; nothing before this class ever consumed that stream to actually
 * do anything with an update. BridgeOrchestrator is that missing link: on
 * start() it starts the repository AND begins collecting `updates`, routing
 * each one through UpdateDispatcher; on stop() it does both in reverse.
 *
 * This is the one object Phase 7's Foreground Service will start/stop —
 * not TelegramRepository directly — since starting the repository alone
 * would poll Telegram without ever dispatching what comes back.
 */
@Singleton
class BridgeOrchestrator @Inject constructor(
    private val telegramRepository: TelegramRepository,
    private val updateDispatcher: UpdateDispatcher
) {
    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dispatchJob: Job? = null

    val state: StateFlow<BridgeServiceState> = telegramRepository.state

    suspend fun start() {
        telegramRepository.start()
        if (dispatchJob?.isActive != true) {
            dispatchJob = orchestratorScope.launch {
                telegramRepository.updates.collect { update -> updateDispatcher.dispatch(update) }
            }
        }
    }

    suspend fun stop() {
        dispatchJob?.cancelAndJoin()
        dispatchJob = null
        telegramRepository.stop()
    }
}
