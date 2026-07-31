package com.sigmabridge.app.domain.model

/**
 * A single translation request. [mode] and [languagePair] are always
 * explicit parameters — nothing here hardcodes "voice" or "Russian to
 * Arabic". The MVP simply always constructs this with
 * mode = TranslationMode.VOICE and languagePair = LanguagePair.DEFAULT_MVP_PAIR.
 *
 * [sourceFile] is a TemporaryVoiceFile, not a raw java.io.File — the
 * domain and presentation layers never handle actual File objects, only
 * this value object naming a path CacheManager owns.
 */
data class TranslationRequest(
    val mode: TranslationMode,
    val languagePair: LanguagePair,
    val sourceFile: TemporaryVoiceFile
)

data class TranslationResult(
    val translatedText: String
)
