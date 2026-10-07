package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TemporaryImageFile

/** Independent Gemini path for explicit Telegram image translation. */
interface ImageTranslationRepository {
    suspend fun translate(
        image: TemporaryImageFile,
        languagePair: LanguagePair
    ): Result<String>
}
