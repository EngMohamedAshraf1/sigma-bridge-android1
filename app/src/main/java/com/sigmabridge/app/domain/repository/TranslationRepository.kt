package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.GeminiHealth
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.model.TranslationResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Performs a translation for a given request. Implemented in Phase 5
 * (Gemini upload/poll/generate/clean, mirroring services/gemini_service.py).
 *
 * Takes a TranslationRequest rather than "an audio file" so the same
 * interface can serve OCR/Photo/PDF modes later without changing its shape.
 *
 * [health] uses Gemini-specific vocabulary (QUOTA_EXCEEDED, AUTHENTICATION_FAILED)
 * even though this interface is nominally provider-agnostic — there is
 * exactly one implementation today and Phase 8.3 asked for these specific
 * states. If a second TranslationRepository implementation is ever added,
 * this is the seam that would need a more provider-neutral health vocabulary.
 */
interface TranslationRepository {
    val health: StateFlow<GeminiHealth>

    suspend fun translate(request: TranslationRequest): Result<TranslationResult>
}
