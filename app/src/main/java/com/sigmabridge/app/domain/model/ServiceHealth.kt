package com.sigmabridge.app.domain.model

/**
 * The set of things Home reports health for. A fixed list today
 * (Telegram, Gemini, Internet, the bridge Service itself); if a fifth
 * component shows up later it's one more enum entry plus one more emission,
 * not a UI change.
 */
enum class HealthComponent {
    TELEGRAM,
    GEMINI,
    INTERNET,
    BRIDGE_SERVICE
}

data class ServiceHealth(
    val component: HealthComponent,
    val status: HealthStatus,
    val detail: String? = null
)
