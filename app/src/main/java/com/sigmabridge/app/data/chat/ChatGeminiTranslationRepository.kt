package com.sigmabridge.app.data.chat

import com.sigmabridge.app.data.gemini.GeminiApiClient
import com.sigmabridge.app.data.gemini.GeminiApiException
import com.sigmabridge.app.domain.gemini.NoAvailableGeminiKeyException
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private Chat's Gemini runtime. This is deliberately separate from
 * GeminiTranslationRepository so Chat has its own key cursor, failure state,
 * retry policy and latency budget. Telegram continues using its original
 * GeminiTranslationRepository unchanged.
 *
 * Chat owns its key cursor and invalid-key state locally; SettingsRepository
 * only supplies the configured key pool. No second Hilt key-manager type is
 * required, keeping dependency injection simple and avoiding cross-runtime
 * coupling.
 */
@Singleton
class ChatGeminiTranslationRepository @Inject constructor(
    private val apiClient: GeminiApiClient,
    private val settingsRepository: SettingsRepository,
    private val logger: BridgeLogger
) {
    private val keyMutex = Mutex()
    private var keyCursor = 0
    private val invalidKeys = mutableSetOf<String>()

    suspend fun translateText(text: String, languagePair: LanguagePair): Result<String> = runCatching {
        withTimeout(TOTAL_TIMEOUT_MS) {
            val maxAttempts = totalKeyCount()
            if (maxAttempts == 0) {
                throw NoAvailableGeminiKeyException("No Gemini API key configured")
            }

            var lastError: Throwable = NoAvailableGeminiKeyException("All configured Gemini keys are unavailable for Chat")
            var attempt = 0

            while (attempt < maxAttempts) {
                val apiKey = nextKey() ?: break
                attempt++
                try {
                    return@withTimeout apiClient.generateTextContent(
                        apiKey = apiKey,
                        model = CHAT_MODEL,
                        prompt = buildPrompt(text, languagePair)
                    )
                        .trim()
                        .also { logger.debug(TAG, "Chat translation succeeded on key #$attempt") }
                } catch (error: GeminiApiException) {
                    lastError = error
                    when (error.httpCode) {
                        HTTP_TOO_MANY_REQUESTS -> {
                            logger.debug(TAG, "Chat key hit HTTP 429; trying next Chat key")
                        }
                        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
                            markInvalid(apiKey)
                            logger.error(TAG, "Chat key authentication failed (${error.httpCode})", error)
                        }
                        else -> throw error
                    }
                } catch (error: Exception) {
                    lastError = error
                    throw error
                }
            }

            throw lastError
        }
    }.onFailure { error ->
        logger.error(TAG, "Chat text translation failed", error)
    }

    private suspend fun totalKeyCount(): Int =
        settingsRepository.geminiApiKeys.valueOrEmpty().size

    private suspend fun nextKey(): String? = keyMutex.withLock {
        val keys = settingsRepository.geminiApiKeys.valueOrEmpty()
        if (keys.isEmpty()) return@withLock null

        val start = keyCursor % keys.size
        var index = start
        do {
            val candidate = keys[index]
            index = (index + 1) % keys.size
            if (candidate !in invalidKeys) {
                keyCursor = index
                return@withLock candidate
            }
        } while (index != start)

        null
    }

    private suspend fun markInvalid(key: String) = keyMutex.withLock {
        invalidKeys += key
    }

    private fun buildPrompt(text: String, languagePair: LanguagePair): String {
        val source = languagePair.source.displayName
        val target = languagePair.target.displayName
        return """
            You are a professional interpreter.
            Translate the following message from $source to natural, fluent $target.

            Rules:
            - Output ONLY the translation.
            - Do not explain, summarize, or add commentary.
            - Preserve names, URLs, email addresses, phone numbers, numbers, emojis, and symbols.
            - Preserve the original intent, tone, and register.
            - Do not translate the speaker's name or proper nouns unless a standard $target form exists.

            Message:
            $text
        """.trimIndent()
    }

    private fun List<String>.valueOrEmpty(): List<String> = this

    private companion object {
        const val TAG = "SigmaBridgeChat"
        const val CHAT_MODEL = "gemini-3.1-flash-lite"
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
