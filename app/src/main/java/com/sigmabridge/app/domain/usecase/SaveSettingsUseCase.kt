package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.repository.SettingsRepository
import javax.inject.Inject

enum class SettingsValidationError {
    BOT_TOKEN_EMPTY,
    BOT_TOKEN_INVALID_FORMAT,
    GEMINI_KEY_EMPTY,
    GEMINI_KEY_INVALID_FORMAT
}

sealed class SaveSettingsResult {
    data object Success : SaveSettingsResult()
    data class ValidationFailed(val errors: List<SettingsValidationError>) : SaveSettingsResult()
}

/**
 * Sole write path for BOT_TOKEN / GEMINI_API_KEY. The ViewModel calls this,
 * never SettingsRepository.saveBotToken/saveGeminiApiKey directly — keeping
 * validation in the domain layer means the same rules apply no matter what
 * UI (or future onboarding flow) collects the values.
 *
 * Format checks are deliberately loose ("obviously invalid", not a strict
 * spec match) — good enough to catch empty fields, pasted whitespace, or an
 * obviously wrong string, without hardcoding assumptions that could reject
 * a legitimately-formatted token/key we haven't seen before.
 */
class SaveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(botToken: String, geminiApiKey: String): SaveSettingsResult {
        val trimmedToken = botToken.trim()
        val trimmedKey = geminiApiKey.trim()
        val errors = mutableListOf<SettingsValidationError>()

        when {
            trimmedToken.isEmpty() -> errors += SettingsValidationError.BOT_TOKEN_EMPTY
            !BOT_TOKEN_REGEX.matches(trimmedToken) -> errors += SettingsValidationError.BOT_TOKEN_INVALID_FORMAT
        }
        when {
            trimmedKey.isEmpty() -> errors += SettingsValidationError.GEMINI_KEY_EMPTY
            !GEMINI_KEY_REGEX.matches(trimmedKey) -> errors += SettingsValidationError.GEMINI_KEY_INVALID_FORMAT
        }

        if (errors.isNotEmpty()) return SaveSettingsResult.ValidationFailed(errors)

        settingsRepository.saveBotToken(trimmedToken)
        settingsRepository.saveGeminiApiKey(trimmedKey)
        return SaveSettingsResult.Success
    }

    private companion object {
        // Telegram bot tokens: numeric bot id, colon, then a long alphanumeric secret.
        val BOT_TOKEN_REGEX = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")

        // Google/Gemini API keys: "AIza" prefix + 35 alphanumeric/underscore/hyphen chars.
        val GEMINI_KEY_REGEX = Regex("^[A-Za-z0-9._-]{20,}$")
    }
}
