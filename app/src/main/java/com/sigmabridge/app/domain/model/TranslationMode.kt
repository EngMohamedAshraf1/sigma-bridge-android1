package com.sigmabridge.app.domain.model

/**
 * The kind of input Sigma Bridge is translating. Voice is the only mode
 * implemented in the MVP; the others exist here so the domain layer, the
 * home screen, and TranslationRequest never need to change shape when
 * OCR/Photos/PDF are actually built in a later phase — only a new
 * TranslationRepository implementation needs to be added.
 */
enum class TranslationMode(val isAvailableInMvp: Boolean) {
    VOICE(isAvailableInMvp = true),
    OCR(isAvailableInMvp = false),
    PHOTO(isAvailableInMvp = false),
    PDF(isAvailableInMvp = false)
}
