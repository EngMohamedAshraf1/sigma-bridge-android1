package com.sigmabridge.app.domain.model

/**
 * Observable state of the Telegram long-polling bridge. The Foreground
 * Service (Phase 7) reacts to this; it does not own it. TelegramRepository
 * is the single source of truth for the current value.
 *
 * DISABLED is the initial state and also where the bridge lands if start()
 * is called without a bot token configured — it isn't "failed", it simply
 * isn't enabled yet. STOPPING is the brief transient window between a stop()
 * call and the polling loop actually finishing (Phase 8.1 made that
 * cancellation prompt instead of blocking, which is what makes this state
 * meaningfully observable instead of instantaneous). FAILED replaces the
 * old ERROR name — same meaning, clearer alongside the rest of this enum.
 */
enum class BridgeServiceState {
    DISABLED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
