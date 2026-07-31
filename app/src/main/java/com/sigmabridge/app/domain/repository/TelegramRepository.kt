package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.BridgeServiceState
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the Telegram long-polling loop and its lifecycle. Implemented in
 * Phase 3 (data layer, actual getUpdates() polling).
 *
 * Deliberately NOT tied to Android's Service class. A Foreground Service
 * (Phase 7) will hold a reference to this repository and call
 * start()/stop()/restart() in response to lifecycle events (onStartCommand,
 * connectivity changes, user taps Stop) — the service only orchestrates,
 * it never contains the polling loop itself. This keeps the polling logic
 * unit-testable without any Android Service instance, and keeps the service
 * class itself thin.
 */
interface TelegramRepository {

    /** Current state of the bridge, observed by the Service and the UI. */
    val state: StateFlow<BridgeServiceState>

    /** Begins long-polling Telegram for updates. Safe to call when already running (no-op). */
    suspend fun start()

    /** Stops long-polling and releases any open connection. Safe to call when already stopped. */
    suspend fun stop()

    /** Equivalent to stop() followed by start(); used for connectivity-loss recovery. */
    suspend fun restart()
}
