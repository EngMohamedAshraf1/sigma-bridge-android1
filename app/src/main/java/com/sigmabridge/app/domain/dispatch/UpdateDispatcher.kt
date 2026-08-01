package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.TelegramUpdate
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Adding a new message type later (text, photo, PDF...) means adding a new
 * UpdateHandler and registering it via Hilt multibinding (see di/DispatchModule) —
 * this class and TelegramRepository's polling loop never change. An update
 * that no registered handler claims is silently ignored (e.g. a text-only
 * message today, since only VoiceMessageHandler exists in Phase 6).
 *
 * dispatch() never lets a handler's exception escape: this runs inside
 * BridgeOrchestrator's single long-lived collector coroutine, so an
 * uncaught exception here would cancel that coroutine and silently stop
 * processing every update after it, not just the one that failed.
 * CancellationException is deliberately rethrown — that's a real
 * cancellation (e.g. the bridge is being stopped), not a handler failure,
 * and must keep propagating for coroutine cancellation to work correctly.
 */
class UpdateDispatcher @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards UpdateHandler>,
    private val logger: BridgeLogger
) {
    suspend fun dispatch(update: TelegramUpdate) {
        val handler = handlers.firstOrNull { it.canHandle(update) } ?: return
        try {
            handler.handle(update)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.error(
                TAG,
                "Handler ${handler::class.simpleName} failed on update ${update.updateId}; continuing with future updates",
                error
            )
        }
    }

    private companion object {
        const val TAG = "SigmaBridge"
    }
}
