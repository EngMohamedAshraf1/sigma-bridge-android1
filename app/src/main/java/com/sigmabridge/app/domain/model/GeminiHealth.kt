package com.sigmabridge.app.domain.model

/** Reflects the outcome of the most recent Gemini call, or BUSY while one is in flight. */
enum class GeminiHealth {
    UNKNOWN,
    READY,
    BUSY,
    QUOTA_EXCEEDED,
    AUTHENTICATION_FAILED,
    NETWORK_ERROR
}
