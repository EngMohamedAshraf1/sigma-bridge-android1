package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.BridgeServiceState
import com.sigmabridge.app.domain.model.TelegramHealth
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
 * Network/parse errors do not stop the loop — they set state to FAILED and
 * retry with exponential backoff, mirroring the retry philosophy already
 * used for Gemini calls in the Python version's gemini_service.py.
 *
 * Phase 8.3 adds [health] alongside [state] as a pure side-effect of the
 * existing try/catch in the poll loop — the retry/backoff logic itself is
 * untouched, this only records what kind of outcome each attempt had.
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

    private val _state = MutableStateFlow(BridgeServiceState.DISABLED)
    override val state: StateFlow<BridgeServiceState> = _state.asStateFlow()

    private val _health = MutableStateFlow(TelegramHealth.UNKNOWN)
    override val health: StateFlow<TelegramHealth> = _health.asStateFlow()

    private val _updates = MutableSharedFlow<TelegramUpdate>(extraBufferCapacity = UPDATE_BUFFER_CAPACITY)
    override val updates: SharedFlow<TelegramUpdate> = _updates.asSharedFlow()

    override suspend fun sendMessage(chatId: Long, text: String, replyToMessageId: Long?): Result<Unit> = runCatching {
        val token = settingsRepository.botToken.first()
            ?: error("Cannot send message: bot token not set")
        apiClient.sendMessage(token, chatId, text, replyToMessageId)
    }

    override suspend fun start() {
        if (pollingJob?.isActive == true) return

        val token = settingsRepository.botToken.first()
        if (token.isNullOrBlank()) {
            // Not configured, not "failed" — DISABLED is the correct state here.
            _state.value = BridgeServiceState.DISABLED
            return
        }

        _state.value = BridgeServiceState.STARTING
        pollingJob = repositoryScope.launch { pollLoop(token) }
    }

    override suspend fun stop() {
        _state.value = BridgeServiceState.STOPPING
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
                _health.value = TelegramHealth.POLLING

                val rawUpdates = apiClient.getUpdates(
                    botToken = token,
                    offset = updateOffset,
                    timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS
                )

                _health.value = TelegramHealth.CONNECTED
                if (_state.value != BridgeServiceState.RUNNING) {
                    _state.value = BridgeServiceState.RUNNING
                }
                backoffMillis = INITIAL_BACKOFF_MS

                rawUpdates.forEach { dto ->
                    // --- TEMPORARY DIAGNOSTIC (remove after root cause found) ---
                    logger.debug(
                        TAG,
                        "RAW update_id=${dto.updateId} chat.id=${dto.message?.chat?.id} " +
                            "chat.type=${dto.message?.chat?.type} message_id=${dto.message?.messageId} " +
                            "from.id=${dto.message?.from?.id} hasVoice=${dto.message?.voice != null}"
                    )
                    // --- END TEMPORARY DIAGNOSTIC ---

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
                _health.value = if (error is TelegramApiException && error.httpCode == HTTP_UNAUTHORIZED) {
                    TelegramHealth.UNAUTHORIZED
                } else {
                    TelegramHealth.NETWORK_ERROR
                }
                _state.value = BridgeServiceState.FAILED
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
        const val HTTP_UNAUTHORIZED = 401
    }
}
