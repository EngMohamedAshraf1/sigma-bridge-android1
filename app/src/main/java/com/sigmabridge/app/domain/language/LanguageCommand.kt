package com.sigmabridge.app.domain.language

/** The result of parsing a /language command's text. Nothing here knows how to read or write a preference. */
sealed class LanguageCommand {
    data object ShowAll : LanguageCommand()
    data object ShowGlobal : LanguageCommand()
    data object ShowUser : LanguageCommand()
    data object ShowChat : LanguageCommand()
    data class SetGlobal(val sourceCode: String, val targetCode: String) : LanguageCommand()
    data class SetUser(val sourceCode: String, val targetCode: String) : LanguageCommand()
    data class SetChat(val sourceCode: String, val targetCode: String) : LanguageCommand()

    /** Starts with /language but doesn't match a known shape - the handler replies with usage help instead of ignoring it. */
    data object Unrecognized : LanguageCommand()
}
