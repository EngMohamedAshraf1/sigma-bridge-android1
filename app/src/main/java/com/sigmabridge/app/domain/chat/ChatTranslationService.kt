package com.sigmabridge.app.domain.chat

import com.sigmabridge.app.data.gemini.GeminiTranslationRepository
import com.sigmabridge.app.domain.gemini.GeminiApiKeyManager
import com.sigmabridge.app.domain.model.Language
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.repository.LanguagePreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private Chat translation facade.
 *
 * Chat can be used from either side of the same configured language pair.
 * We therefore detect which side the actual text belongs to when possible and
 * translate to the opposite language. This keeps both devices working even
 * when they share the same global language pair (for example Arabic -> Russian).
 */
@Singleton
class ChatTranslationService @Inject constructor(
    private val geminiRepository: GeminiTranslationRepository,
    private val keyManager: GeminiApiKeyManager,
    private val languagePreferencesRepository: LanguagePreferencesRepository
) {
    suspend fun translateIncoming(text: String): Result<String> {
        if (keyManager.totalKeyCount() == 0) return Result.success(text)
        val configured = languagePreferencesRepository.getGlobal().toLanguagePair()
        val pair = choosePairForText(text, configured, fallbackToConfigured = true)
        return geminiRepository.translateText(text, pair)
    }

    suspend fun translateOutgoing(text: String): Result<String> {
        if (keyManager.totalKeyCount() == 0) return Result.success(text)
        val configured = languagePreferencesRepository.getGlobal().toLanguagePair()
        val pair = choosePairForText(text, configured, fallbackToConfigured = false)
        return geminiRepository.translateText(text, pair)
    }

    /**
     * Prefer the direction that matches the actual message language. For the
     * common Arabic/Russian pair this is deterministic from Unicode script and
     * avoids requiring a language-selection role on each device.
     */
    private fun choosePairForText(
        text: String,
        configured: LanguagePair,
        fallbackToConfigured: Boolean
    ): LanguagePair {
        val detectedCode = detectSimpleLanguage(text)
        return when (detectedCode) {
            configured.source.code -> configured
            configured.target.code -> LanguagePair(source = configured.target, target = configured.source)
            else -> if (fallbackToConfigured) {
                configured
            } else {
                LanguagePair(source = configured.target, target = configured.source)
            }
        }
    }

    /** Lightweight script detection for chat; no extra network/model call. */
    private fun detectSimpleLanguage(text: String): String? {
        var arabic = 0
        var cyrillic = 0
        var latin = 0

        text.forEach { ch ->
            when {
                ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF' -> arabic++
                ch in '\u0400'..'\u04FF' -> cyrillic++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }

        val total = arabic + cyrillic + latin
        if (total == 0) return null

        return when {
            arabic > cyrillic && arabic > latin -> "ar"
            cyrillic > arabic && cyrillic > latin -> "ru"
            latin > arabic && latin > cyrillic -> "en"
            else -> null
        }
    }
}
