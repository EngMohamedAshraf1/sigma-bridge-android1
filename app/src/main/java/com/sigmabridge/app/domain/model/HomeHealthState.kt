package com.sigmabridge.app.domain.model

/** Everything Home's Status section renders, combined into one reactive snapshot. */
data class HomeHealthState(
    val bridge: BridgeServiceState,
    val telegram: TelegramHealth,
    val gemini: GeminiHealth,
    val internet: InternetHealth
)
