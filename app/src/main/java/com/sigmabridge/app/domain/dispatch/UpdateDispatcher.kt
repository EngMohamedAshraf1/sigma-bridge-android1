package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.model.TelegramUpdate
import javax.inject.Inject

/**
 * Adding a new message type later (text, photo, PDF...) means adding a new
 * UpdateHandler and registering it via Hilt multibinding (see di/DispatchModule) —
 * this class and TelegramRepository's polling loop never change. An update
 * that no registered handler claims is silently ignored (e.g. a text-only
 * message today, since only VoiceMessageHandler exists in Phase 6).
 */
class UpdateDispatcher @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards UpdateHandler>
) {
    suspend fun dispatch(update: TelegramUpdate) {
        handlers.firstOrNull { it.canHandle(update) }?.handle(update)
    }
}
