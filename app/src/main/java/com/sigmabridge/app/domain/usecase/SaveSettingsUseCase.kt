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
 * Sole write path for BOT_TOKEN / Gemini key pool. The ViewModel calls
 * this, never SettingsRepository.saveBotToken/saveGeminiApiKeys directly —
 * keeping validation in the domain layer means the same rules apply no
 * matter what UI (or future onboarding flow) collects the values.
 *
 * Format checks are deliberately loose ("obviously invalid", not a strict
 * spec match) — good enough to catch empty fields, pasted whitespace, or an
 * obviously wrong string, without hardcoding assumptions that could reject
 * a legitimately-formatted token/key we haven't seen before. GEMINI_KEY_REGEX
 * is unchanged from before the multi-key UI — still accepts both AI... and
 * AQ... formats; only how many strings it's checked against changed.
 */
class SaveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(botToken: String, geminiApiKeys: List<String>): SaveSettingsResult {
        val trimmedToken = botToken.trim()
        val trimmedKeys = geminiApiKeys.map { it.trim() }.filter { it.isNotEmpty() }
        val errors = mutableListOf<SettingsValidationError>()

        when {
            trimmedToken.isEmpty() -> errors += SettingsValidationError.BOT_TOKEN_EMPTY
            !BOT_TOKEN_REGEX.matches(trimmedToken) -> errors += SettingsValidationError.BOT_TOKEN_INVALID_FORMAT
        }

        when {
            trimmedKeys.isEmpty() -> errors += SettingsValidationError.GEMINI_KEY_EMPTY
            trimmedKeys.any { !GEMINI_KEY_REGEX.matches(it) } -> errors += SettingsValidationError.GEMINI_KEY_INVALID_FORMAT
        }

        if (errors.isNotEmpty()) return SaveSettingsResult.ValidationFailed(errors)

        settingsRepository.saveBotToken(trimmedToken)
        settingsRepository.saveGeminiApiKeys(trimmedKeys)
        return SaveSettingsResult.Success
    }

    private companion object {
        // Telegram bot tokens: numeric bot id, colon, then a long alphanumeric secret.
        val BOT_TOKEN_REGEX = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")

        // Google/Gemini API keys: unchanged since before the multi-key UI - accepts both
        // AI... and AQ... (and any other) prefix, 20+ chars of [A-Za-z0-9._-].
        val GEMINI_KEY_REGEX = Regex("^[A-Za-z0-9._-]{20,}$")
    }
}
