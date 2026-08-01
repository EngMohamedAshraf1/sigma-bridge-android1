package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.BridgeServiceState
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.repository.SettingsRepository
import com.sigmabridge.app.domain.repository.TelegramRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Equivalent of main.py's `application.run_polling()`, but as an
 * explicitly start/stop/restart-able component instead of a blocking call
 * that owns the process. No callback-based API anywhere: the polling loop
 * is a suspend function, results are pushed through a SharedFlow, and
 * state is a StateFlow.
 *
 * Network/parse errors do not stop the loop — they set state to ERROR and
 * retry with exponential backoff, mirroring the retry philosophy already
 * used for Gemini calls in the Python version's gemini_service.py.
 */
@Singleton
class TelegramRepositoryImpl @Inject constructor(
    private val apiClient: TelegramApiClient,
    private val settingsRepository: SettingsRepository,
    private val logger: BridgeLogger
) : TelegramRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var updateOffset: Long? = null

    // High-water-mark guard: Telegram's offset mechanism should already prevent
    // redelivery, but the API contract allows for it in edge cases. Cheap to check,
    // and directly prevents ever dispatching (and re-translating/re-replying to) the
    // same voice message twice within one running session.
    private var lastEmittedUpdateId: Long? = null

    private val _state = MutableStateFlow(BridgeServiceState.STOPPED)
    override val state: StateFlow<BridgeServiceState> = _state.asStateFlow()

    private val _updates = MutableSharedFlow<TelegramUpdate>(extraBufferCapacity = UPDATE_BUFFER_CAPACITY)
    override val updates: SharedFlow<TelegramUpdate> = _updates.asSharedFlow()

    override suspend fun sendMessage(chatId: Long, text: String): Result<Unit> = runCatching {
        val token = settingsRepository.botToken.first()
            ?: error("Cannot send message: bot token not set")
        apiClient.sendMessage(token, chatId, text)
    }

    override suspend fun start() {
        if (pollingJob?.isActive == true) return

        val token = settingsRepository.botToken.first()
        if (token.isNullOrBlank()) {
            _state.value = BridgeServiceState.ERROR
            return
        }

        _state.value = BridgeServiceState.STARTING
        pollingJob = repositoryScope.launch { pollLoop(token) }
    }

    override suspend fun stop() {
        pollingJob?.cancelAndJoin()
        pollingJob = null
        _state.value = BridgeServiceState.STOPPED
    }

    override suspend fun restart() {
        stop()
        start()
    }

    private suspend fun pollLoop(token: String) {
        var backoffMillis = INITIAL_BACKOFF_MS

        while (currentCoroutineContext().isActive) {
            try {
                val rawUpdates = apiClient.getUpdates(
                    botToken = token,
                    offset = updateOffset,
                    timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS
                )

                if (_state.value != BridgeServiceState.RUNNING) {
                    _state.value = BridgeServiceState.RUNNING
                }
                backoffMillis = INITIAL_BACKOFF_MS

                rawUpdates.forEach { dto ->
                    updateOffset = dto.updateId + 1

                    val alreadySeen = lastEmittedUpdateId?.let { dto.updateId <= it } ?: false
                    if (alreadySeen) {
                        logger.debug(TAG, "Skipping already-seen update ${dto.updateId}")
                        return@forEach
                    }
                    lastEmittedUpdateId = dto.updateId

                    dto.toDomain()?.let { _updates.emit(it) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.error(TAG, "Telegram polling error; retrying with backoff", error)
                _state.value = BridgeServiceState.ERROR
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private companion object {
        const val TAG = "SigmaBridge"
        const val LONG_POLL_TIMEOUT_SECONDS = 30
        const val UPDATE_BUFFER_CAPACITY = 64
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
