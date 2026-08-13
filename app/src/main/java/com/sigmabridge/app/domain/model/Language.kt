package com.sigmabridge.app.domain.model

import com.sigmabridge.app.domain.language.LanguageCatalog

/**
 * A spoken/written language identified by its ISO 639-1 code.
 *
 * This is intentionally a plain data holder, not an enum — the list of
 * which languages exist now lives entirely in LanguageCatalog
 * (domain/language/), not here. This class no longer knows what languages
 * are supported; it only defines what a Language IS.
 */
data class Language(
    val code: String,
    val displayName: String
)

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
        val DEFAULT_MVP_PAIR = LanguagePair(source = LanguageCatalog.RUSSIAN, target = LanguageCatalog.ARABIC)
    }
}
