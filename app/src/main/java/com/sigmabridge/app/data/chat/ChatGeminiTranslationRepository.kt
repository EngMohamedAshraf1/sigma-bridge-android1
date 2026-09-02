package com.sigmabridge.app.data.chat

import com.sigmabridge.app.data.gemini.GeminiApiClient
import com.sigmabridge.app.data.gemini.GeminiApiException
import com.sigmabridge.app.domain.gemini.NoAvailableGeminiKeyException
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.LanguagePair
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private Chat's Gemini runtime. This is deliberately separate from
 * GeminiTranslationRepository so Chat has its own key cursor, failure state,
 * retry policy and latency budget. Telegram continues using its original
 * GeminiTranslationRepository unchanged.
 */
@Singleton
class ChatGeminiTranslationRepository @Inject constructor(
    private val apiClient: GeminiApiClient,
    private val keyManager: ChatGeminiApiKeyManager,
    private val logger: BridgeLogger
) {
    suspend fun translateText(text: String, languagePair: LanguagePair): Result<String> = runCatching {
        withTimeout(TOTAL_TIMEOUT_MS) {
            val maxAttempts = keyManager.totalKeyCount()
            if (maxAttempts == 0) {
                throw NoAvailableGeminiKeyException("No Gemini API key configured")
            }

            var lastError: Throwable = NoAvailableGeminiKeyException("All configured Gemini keys are unavailable for Chat")
            var attempt = 0

            while (attempt < maxAttempts) {
                val apiKey = keyManager.nextKey() ?: break
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
                            keyManager.markInvalid(apiKey)
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

    private companion object {
        const val TAG = "SigmaBridgeChat"
        const val CHAT_MODEL = "gemini-3.1-flash-lite"
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
