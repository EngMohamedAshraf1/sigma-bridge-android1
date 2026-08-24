package com.sigmabridge.app.domain.chat

import com.sigmabridge.app.data.gemini.GeminiTranslationRepository
import com.sigmabridge.app.domain.gemini.GeminiApiKeyManager
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.repository.LanguagePreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private Chat translation facade.
 *
 * The phone that has Gemini keys becomes the translation host automatically.
 * A peer without configured keys simply passes its messages through unchanged.
 * The existing global language configuration is the source -> target direction
 * for incoming messages; outgoing messages use the reversed pair.
 */
@Singleton
class ChatTranslationService @Inject constructor(
    private val geminiRepository: GeminiTranslationRepository,
    private val keyManager: GeminiApiKeyManager,
    private val languagePreferencesRepository: LanguagePreferencesRepository
) {
    suspend fun translateIncoming(text: String): Result<String> {
        if (keyManager.totalKeyCount() == 0) return Result.success(text)
        val pair = languagePreferencesRepository.getGlobal().toLanguagePair()
        return geminiRepository.translateText(text, pair)
    }

    suspend fun translateOutgoing(text: String): Result<String> {
        if (keyManager.totalKeyCount() == 0) return Result.success(text)
        val configured = languagePreferencesRepository.getGlobal().toLanguagePair()
        val reverse = LanguagePair(source = configured.target, target = configured.source)
        return geminiRepository.translateText(text, reverse)
    }
}
