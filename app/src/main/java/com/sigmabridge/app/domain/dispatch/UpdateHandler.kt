package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.model.TelegramUpdate

/**
 * One handler per update "kind" (voice today; text, photo, document, etc.
 * later). TelegramRepository's polling loop never checks update content —
 * it only emits raw TelegramUpdate. UpdateDispatcher is what decides which
 * handler, if any, an update belongs to.
 */
interface UpdateHandler {
    fun canHandle(update: TelegramUpdate): Boolean
    suspend fun handle(update: TelegramUpdate)
}
