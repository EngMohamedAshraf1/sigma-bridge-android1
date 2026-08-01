package com.sigmabridge.app.domain.logging

/**
 * The domain layer (UpdateDispatcher, VoiceMessageHandler, etc.) needs to
 * log failures without depending on android.util.Log directly — every
 * other domain/data boundary in this app follows the same shape
 * (interface in domain, implementation in data, bound via Hilt), and
 * logging is no exception.
 */
interface BridgeLogger {
    fun debug(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}
