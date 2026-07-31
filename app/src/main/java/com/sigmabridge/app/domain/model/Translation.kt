package com.sigmabridge.app.domain.model

import java.io.File

/**
 * A single translation request. [mode] and [languagePair] are always
 * explicit parameters — nothing here hardcodes "voice" or "Russian to
 * Arabic". The MVP simply always constructs this with
 * mode = TranslationMode.VOICE and languagePair = LanguagePair.DEFAULT_MVP_PAIR.
 */
data class TranslationRequest(
    val mode: TranslationMode,
    val languagePair: LanguagePair,
    val sourceFile: File
)

data class TranslationResult(
    val translatedText: String
)
