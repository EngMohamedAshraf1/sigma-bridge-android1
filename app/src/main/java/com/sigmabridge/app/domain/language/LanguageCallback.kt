package com.sigmabridge.app.domain.language

/** The result of parsing a button's callback_data. Nothing here knows how to read/write a preference or talk to Telegram. */
sealed class LanguageCallback {
    data class SelectScope(val scope: LanguageScope) : LanguageCallback()
    data class SelectSource(val scope: LanguageScope, val sourceCode: String) : LanguageCallback()
    data class SelectTarget(val scope: LanguageScope, val sourceCode: String, val targetCode: String) : LanguageCallback()
}
