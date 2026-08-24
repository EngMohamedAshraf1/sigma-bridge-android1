package com.sigmabridge.app.domain.model

/**
 * The kind of input Sigma Bridge is translating. Voice and Audio are now
 * supported media modes; the remaining modes are reserved for future phases.
 */
enum class TranslationMode(val isAvailableInMvp: Boolean) {
    VOICE(isAvailableInMvp = true),
    AUDIO(isAvailableInMvp = true),
    OCR(isAvailableInMvp = false),
    PHOTO(isAvailableInMvp = false),
    PDF(isAvailableInMvp = false)
}
