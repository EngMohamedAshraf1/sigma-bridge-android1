package com.sigmabridge.app.domain.language

/** The same three scopes /language user|chat|global already address — shared vocabulary for the new interactive flow. */
enum class LanguageScope(val callbackKey: String, val label: String) {
    USER("user", "My Language"),
    CHAT("chat", "Group Language"),
    GLOBAL("global", "Global Language");

    companion object {
        fun fromKey(key: String): LanguageScope? = entries.firstOrNull { it.callbackKey == key }
    }
}
