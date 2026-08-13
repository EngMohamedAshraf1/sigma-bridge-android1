package com.sigmabridge.app.domain.language

import com.sigmabridge.app.domain.model.Language

/**
 * The single source of truth for every language this app supports.
 * Every other component — validation (LanguageCommandHandler), the stored
 * code -> Language lookup (SecureLanguagePreferencesRepository), help text
 * and "Supported languages" error text (LanguageCommandHandler),
 * LanguagePair.DEFAULT_MVP_PAIR — reads from [ALL] or [findByCode] here.
 * None of them define their own list anymore.
 *
 * Adding a new language means adding one entry to [ALL] (and, if it needs
 * a named constant elsewhere, one val here) — nothing outside this file
 * needs to change.
 */
object LanguageCatalog {
    val RUSSIAN = Language(code = "ru", displayName = "Russian")
    val ARABIC = Language(code = "ar", displayName = "Arabic")
    val AUTO_DETECT = Language(code = "auto", displayName = "Auto-detect")

    val ALL: List<Language> = listOf(
        Language(code = "en", displayName = "English"),
        RUSSIAN,
        ARABIC,
        Language(code = "fr", displayName = "French"),
        Language(code = "de", displayName = "German"),
        Language(code = "es", displayName = "Spanish"),
        Language(code = "it", displayName = "Italian"),
        Language(code = "pt", displayName = "Portuguese"),
        Language(code = "tr", displayName = "Turkish"),
        Language(code = "zh", displayName = "Chinese (Simplified)"),
        Language(code = "ja", displayName = "Japanese"),
        Language(code = "ko", displayName = "Korean"),
        Language(code = "hi", displayName = "Hindi"),
        Language(code = "uk", displayName = "Ukrainian"),
        Language(code = "pl", displayName = "Polish"),
        AUTO_DETECT
    )

    fun findByCode(code: String): Language? = ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }
}
