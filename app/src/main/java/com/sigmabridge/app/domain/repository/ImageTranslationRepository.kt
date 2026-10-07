package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.GeminiHealth
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TemporaryImageFile
import kotlinx.coroutines.flow.StateFlow

/** Independent Gemini path for explicit Telegram image translation. */
interface ImageTranslationRepository {
    val health: StateFlow<GeminiHealth>
    suspend fun translate(
        image: TemporaryImageFile,
        languagePair: LanguagePair
    ): Result<String>
}
