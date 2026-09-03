package com.sigmabridge.app.domain.chat

import com.sigmabridge.app.data.chat.ChatGeminiTranslationRepository
import com.sigmabridge.app.data.chat.ChatLanguagePreferences
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.LanguagePair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private Chat translation facade. Chat uses its own Gemini runtime so its
 * health, key rotation and timeout behavior cannot mutate Telegram's Gemini
 * runtime. The target language is local to Private Chat on each device.
 *
 * Translation is optional for transport: a device without a local Gemini key
 * simply displays the decrypted original message and never reports a Gemini
 * configuration error to the user.
 */
@Singleton
class ChatTranslationService @Inject constructor(
    private val geminiRepository: ChatGeminiTranslationRepository,
    private val languagePreferences: ChatLanguagePreferences
) {
    fun targetLanguage() = languagePreferences.getTargetLanguage()

    fun setTargetLanguage(code: String) {
        LanguageCatalog.findByCode(code)?.let(languagePreferences::setTargetLanguage)
    }

    suspend fun translateIncoming(text: String): Result<String> {
        val target = languagePreferences.getTargetLanguage()
        val sourceCode = detectSimpleLanguage(text)
            ?: return Result.success(text)
        if (sourceCode == target.code || target.code == LanguageCatalog.AUTO_DETECT.code) {
            return Result.success(text)
        }

        // Gemini is optional on Private Chat devices. If this device has no
        // local key configured, return the original decrypted text silently.
        if (!geminiRepository.hasConfiguredKeys()) {
            return Result.success(text)
        }

        val source = LanguageCatalog.findByCode(sourceCode)
            ?: return Result.success(text)
        return geminiRepository.translateText(
            text,
            LanguagePair(source = source, target = target)
        )
    }

    suspend fun translateOutgoing(text: String): Result<String> = translateIncoming(text)

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
