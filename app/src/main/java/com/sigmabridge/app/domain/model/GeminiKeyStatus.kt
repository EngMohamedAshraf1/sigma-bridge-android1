package com.sigmabridge.app.domain.model

/**
 * Real runtime status of one configured Gemini key, as tracked by
 * GeminiApiKeyManager. Never fabricated by the UI — this is exactly what
 * the manager reports.
 */
enum class GeminiKeyStatus {
    /** Currently selected by GeminiApiKeyManager's round-robin — the key an in-flight or just-finished translate() call used. */
    ACTIVE,

    /** Valid, not currently selected. */
    READY,

    /** Hit HTTP 429 on its most recent attempt. Still eligible to be selected again later — quotas can reset. */
    QUOTA_EXCEEDED,

    /** Hit HTTP 401/403. Excluded from selection for the rest of this process's lifetime. */
    INVALID
}
