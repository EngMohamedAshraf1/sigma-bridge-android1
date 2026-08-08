package com.sigmabridge.app.domain.model

/**
 * A persisted language preference — structurally the same shape as
 * LanguagePair (source + target), but a distinct type on purpose:
 * LanguagePreferencesRepository stores/returns this, while LanguagePair
 * stays the type TranslationRequest actually carries into a live
 * translation call. Phase 9.1 intentionally does not wire the two
 * together anywhere in the pipeline — toLanguagePair() exists so a future
 * phase can, in one line, without redesigning either type.
 */
data class LanguageConfiguration(
    val source: Language,
    val target: Language
) {
    fun toLanguagePair(): LanguagePair = LanguagePair(source = source, target = target)

    companion object {
        fun from(languagePair: LanguagePair): LanguageConfiguration =
            LanguageConfiguration(source = languagePair.source, target = languagePair.target)

        /** Mirrors LanguagePair.DEFAULT_MVP_PAIR exactly — what every getter falls back to until something is explicitly configured. */
        val DEFAULT: LanguageConfiguration = from(LanguagePair.DEFAULT_MVP_PAIR)
    }
}
