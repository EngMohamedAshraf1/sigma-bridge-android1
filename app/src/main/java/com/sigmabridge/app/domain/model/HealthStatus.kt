package com.sigmabridge.app.domain.model

/**
 * Generic status for anything Home needs to show a health indicator for.
 * [UNKNOWN] is the Phase 2 default for everything — no real check exists
 * yet (Telegram polling is Phase 3, Gemini calls are Phase 5, connectivity
 * observation is Phase 7/8). The enum exists now so HealthCard and
 * HealthComponent never need to change shape once real checks land; only
 * the value being emitted changes.
 */
enum class HealthStatus {
    UNKNOWN,
    CHECKING,
    HEALTHY,
    ERROR,
    DISABLED
}
