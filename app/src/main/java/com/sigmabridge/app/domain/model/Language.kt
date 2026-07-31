package com.sigmabridge.app.domain.model

/**
 * A spoken/written language identified by its ISO 639-1 code.
 *
 * This is intentionally a plain data holder, not an enum. An enum would mean
 * every new language requires a code change and a rebuild; a data class lets
 * the language list come from a repository/remote config later without
 * touching this type. [AUTO_DETECT] is the one language-agnostic constant we
 * keep, since Gemini can detect the spoken language itself.
 */
data class Language(
    val code: String,
    val displayName: String
) {
    companion object {
        val AUTO_DETECT = Language(code = "auto", displayName = "Auto-detect")
        val ARABIC = Language(code = "ar", displayName = "Arabic")
        val RUSSIAN = Language(code = "ru", displayName = "Russian")

        /** Languages available to pick from today. Grows without touching call sites. */
        val SUPPORTED: List<Language> = listOf(RUSSIAN, ARABIC, AUTO_DETECT)
    }
}

/**
 * A source -> target pairing for a single translation. Kept separate from
 * [Language] itself so a repository can persist "the user's chosen pair"
 * independently of the catalog of known languages.
 */
data class LanguagePair(
    val source: Language,
    val target: Language
) {
    companion object {
        /** The only pair enabled in the MVP. Everything downstream still takes a LanguagePair, never these two Languages directly. */
        val DEFAULT_MVP_PAIR = LanguagePair(source = Language.RUSSIAN, target = Language.ARABIC)
    }
}
