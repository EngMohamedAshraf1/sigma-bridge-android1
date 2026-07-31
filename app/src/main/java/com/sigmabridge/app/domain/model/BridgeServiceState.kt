package com.sigmabridge.app.domain.model

/**
 * Observable state of the Telegram long-polling bridge. The Foreground
 * Service (Phase 7) reacts to this; it does not own it. TelegramRepository
 * is the single source of truth for the current value.
 */
enum class BridgeServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}
