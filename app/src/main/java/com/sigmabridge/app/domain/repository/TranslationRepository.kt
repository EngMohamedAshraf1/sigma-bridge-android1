package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.model.TranslationResult

/**
 * Performs a translation for a given request. Implemented in Phase 5
 * (Gemini upload/poll/generate/clean, mirroring services/gemini_service.py).
 *
 * Takes a TranslationRequest rather than "an audio file" so the same
 * interface can serve OCR/Photo/PDF modes later without changing its shape.
 */
interface TranslationRepository {
    suspend fun translate(request: TranslationRequest): Result<TranslationResult>
}
