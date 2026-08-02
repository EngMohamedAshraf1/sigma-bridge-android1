package com.sigmabridge.app.domain.model

/**
 * LIMITED means the device has an active network interface (Wi-Fi/cellular)
 * without validated internet access (e.g. a captive portal) — distinct from
 * fully OFFLINE (no active network interface at all).
 */
enum class InternetHealth {
    UNKNOWN,
    CONNECTED,
    LIMITED,
    OFFLINE
}
