package com.sigmabridge.app.domain.model

/**
 * Connection-level health of the Telegram long-poll, distinct from
 * [BridgeServiceState] (which is the bridge's overall lifecycle). A bridge
 * can be RUNNING while its Telegram connection is currently NETWORK_ERROR
 * mid-retry, for example — these are two different questions.
 */
enum class TelegramHealth {
    UNKNOWN,
    POLLING,
    CONNECTED,
    UNAUTHORIZED,
    NETWORK_ERROR
}
