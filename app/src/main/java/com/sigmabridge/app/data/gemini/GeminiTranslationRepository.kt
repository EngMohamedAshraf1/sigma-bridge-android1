package com.sigmabridge.app.data.gemini

import com.sigmabridge.app.data.gemini.dto.GeminiFileDto
import com.sigmabridge.app.domain.gemini.GeminiApiKeyManager
import com.sigmabridge.app.domain.gemini.NoAvailableGeminiKeyException
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.GeminiHealth
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.model.TranslationResult
import com.sigmabridge.app.domain.repository.TranslationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TranslationRepository stays an interface with exactly one implementation
 * here — nothing else in the app is allowed to depend on GeminiApiClient
 * or any Gemini-specific type directly; everyone else depends on
 * TranslationRepository.
 *
 * The existing file/audio path is unchanged. Phase Chat adds [translateText]
 * as an additive text-only entry point so Private Chat can reuse the same
 * Gemini key manager, rotation, status tracking, and transient retry policy.
 */
@Singleton
class GeminiTranslationRepository @Inject constructor(
    private val apiClient: GeminiApiClient,
    private val keyManager: GeminiApiKeyManager,
    private val logger: BridgeLogger
) : TranslationRepository {

    private val _health = MutableStateFlow(GeminiHealth.UNKNOWN)
    override val health: StateFlow<GeminiHealth> = _health.asStateFlow()

    /**
     * Tries each configured key at most once, in GeminiApiKeyManager's
     * deterministic round-robin order. 429 moves to the next key; 401/403
     * mark the current key invalid for this process. Other failures propagate
     * after the bounded transient retry inside translateWithKey.
     */
    override suspend fun translate(request: TranslationRequest): Result<TranslationResult> = runCatching {
        _health.value = GeminiHealth.BUSY

        val maxAttempts = keyManager.totalKeyCount()
        if (maxAttempts == 0) {
            throw NoAvailableGeminiKeyException("No Gemini API key configured")
        }

        var lastError: Throwable = NoAvailableGeminiKeyException("All configured Gemini API keys are unavailable")
        var attempt = 0

        while (attempt < maxAttempts) {
            val apiKey = keyManager.nextKey() ?: break
            attempt++
            try {
                return@runCatching translateWithKey(apiKey, request).also {
                    keyManager.markSucceeded(apiKey)
                }
            } catch (error: GeminiApiException) {
                lastError = error
                when (error.httpCode) {
                    HTTP_TOO_MANY_REQUESTS -> {
                        logger.debug(TAG, "Key ending in \"${apiKey.takeLast(4)}\" hit quota (429); trying next key")
                        keyManager.markQuotaExceeded(apiKey)
                    }
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
                        logger.error(TAG, "Key ending in \"${apiKey.takeLast(4)}\" failed auth (${error.httpCode}); marking invalid for this session", error)
                        keyManager.markInvalid(apiKey)
                    }
                    else -> throw error
                }
            }
        }

        throw lastError
    }.onSuccess {
        _health.value = GeminiHealth.READY
    }.onFailure { error ->
        logger.error(TAG, "Translation failed for ${request.sourceFile.id}", error)
        _health.value = classifyFailure(error)
    }

    /**
     * Text-only Gemini entry point used by Private Chat.
     * It deliberately reuses the same key manager and retry policy as the
     * existing audio pipeline but does not alter the TranslationRepository
     * contract or the Telegram path.
     */
    suspend fun translateText(text: String, languagePair: LanguagePair): Result<String> = runCatching {
        _health.value = GeminiHealth.BUSY

        val maxAttempts = keyManager.totalKeyCount()
        if (maxAttempts == 0) {
            throw NoAvailableGeminiKeyException("No Gemini API key configured")
        }

        var lastError: Throwable = NoAvailableGeminiKeyException("All configured Gemini API keys are unavailable")
        var attempt = 0

        while (attempt < maxAttempts) {
            val apiKey = keyManager.nextKey() ?: break
            attempt++
            try {
                return@runCatching translateTextWithKey(apiKey, text, languagePair).also {
                    keyManager.markSucceeded(apiKey)
                }
            } catch (error: GeminiApiException) {
                lastError = error
                when (error.httpCode) {
                    HTTP_TOO_MANY_REQUESTS -> {
                        logger.debug(TAG, "Chat key ending in \"${apiKey.takeLast(4)}\" hit quota (429); trying next key")
                        keyManager.markQuotaExceeded(apiKey)
                    }
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
                        logger.error(TAG, "Chat key ending in \"${apiKey.takeLast(4)}\" failed auth (${error.httpCode}); marking invalid for this session", error)
                        keyManager.markInvalid(apiKey)
                    }
                    else -> throw error
                }
            }
        }

        throw lastError
    }.onSuccess {
        _health.value = GeminiHealth.READY
    }.onFailure { error ->
        logger.error(TAG, "Chat text translation failed", error)
        _health.value = classifyFailure(error)
    }

    private suspend fun translateWithKey(apiKey: String, request: TranslationRequest): TranslationResult {
        var uploadedFile: GeminiFileDto? = null
        try {
            val mimeType = request.sourceFile.mimeType
            uploadedFile = apiClient.uploadFile(
                apiKey = apiKey,
                sourceFilePath = request.sourceFile.path,
                mimeType = mimeType,
                displayName = request.sourceFile.id
            )

            val activeFile = awaitActiveState(apiKey, uploadedFile)
            val fileUri = activeFile.uri ?: error("Gemini file has no uri after becoming ACTIVE")

            val prompt = buildPrompt(request.languagePair)
            val rawText = withRetryOnTransientFailure {
                apiClient.generateContent(
                    apiKey = apiKey,
                    model = MODEL,
                    prompt = prompt,
                    fileUri = fileUri,
                    mimeType = mimeType
                )
            }

            return TranslationResult(translatedText = cleanTranslation(rawText))
        } finally {
            uploadedFile?.let { runCatching { apiClient.deleteFile(apiKey, it.name) } }
        }
    }

    private suspend fun translateTextWithKey(
        apiKey: String,
        text: String,
        languagePair: LanguagePair
    ): String {
        val prompt = buildTextPrompt(text, languagePair)
        val rawText = withRetryOnTransientFailure {
            apiClient.generateTextContent(
                apiKey = apiKey,
                model = MODEL,
                prompt = prompt
            )
        }
        return cleanTranslation(rawText)
    }

    private fun classifyFailure(error: Throwable): GeminiHealth {
        val effective = if (error is NoAvailableGeminiKeyException) error.cause ?: error else error
        return when {
            effective is GeminiApiException && effective.httpCode == HTTP_TOO_MANY_REQUESTS -> GeminiHealth.QUOTA_EXCEEDED
            effective is GeminiApiException && (effective.httpCode == HTTP_UNAUTHORIZED || effective.httpCode == HTTP_FORBIDDEN) ->
                GeminiHealth.AUTHENTICATION_FAILED
            else -> GeminiHealth.NETWORK_ERROR
        }
    }

    private suspend fun awaitActiveState(apiKey: String, file: GeminiFileDto): GeminiFileDto {
        var current = file
        var waitedMillis = 0L
        while (current.state != STATE_ACTIVE) {
            if (waitedMillis >= ACTIVE_POLL_TIMEOUT_MS) {
                error("Gemini file did not become ACTIVE within ${ACTIVE_POLL_TIMEOUT_MS}ms")
            }
            delay(ACTIVE_POLL_INTERVAL_MS)
            waitedMillis += ACTIVE_POLL_INTERVAL_MS
            current = apiClient.getFile(apiKey, current.name)
        }
        return current
    }

    /** Retry 408 and 5xx generation failures with exponential backoff. */
    private suspend fun <T> withRetryOnTransientFailure(block: suspend () -> T): T {
        var attempt = 0
        var backoffMillis = INITIAL_BACKOFF_MS
        while (true) {
            try {
                return block()
            } catch (error: GeminiApiException) {
                attempt++
                val retryable = error.httpCode == HTTP_REQUEST_TIMEOUT ||
                    error.httpCode == HTTP_INTERNAL_SERVER_ERROR ||
                    error.httpCode == HTTP_SERVICE_UNAVAILABLE ||
                    error.httpCode == HTTP_GATEWAY_TIMEOUT

                if (!retryable || attempt >= MAX_RETRY_ATTEMPTS) {
                    throw error
                }

                logger.debug(
                    TAG,
                    "Transient Gemini HTTP ${error.httpCode}; retrying attempt $attempt/$MAX_RETRY_ATTEMPTS in ${backoffMillis}ms"
                )
                delay(backoffMillis)
                backoffMillis = minOf(backoffMillis * 2, MAX_BACKOFF_MS)
            }
        }
    }

    /** One request, one direction: the model listens and outputs only the translation. */
    private fun buildPrompt(languagePair: LanguagePair): String {
        val source = languagePair.source.displayName
        val target = languagePair.target.displayName
        return """
            You are a professional simultaneous interpreter, not a literal machine translator.
            Listen to the attached audio in $source and produce a natural, fluent $target
            translation of what is said — the meaning and tone a native $target speaker would
            actually use, not a word-for-word rendering.

            Follow these rules exactly:
            - Output ONLY the translation. No transcription of the source language, no
              explanations, no notes, no markdown formatting, no labels like "Translation:".
            - Preserve proper nouns (people's names, place names, brand names) as spoken —
              transliterate into $target script only if there is no established convention;
              never translate or replace a name.
            - Preserve URLs, email addresses, phone numbers, and numeric values (dates, prices,
              quantities) exactly as spoken, without altering their format.
            - Preserve emojis, symbols, and any formatting present in the speaker's words exactly
              as they occur.
            - Use natural punctuation and sentence breaks that match how the sentence would
              actually be written in $target, not the exact pause structure of the spoken audio.
            - If the speaker repeats themselves, stutters, or restarts a sentence, translate the
              intended final meaning once — do not duplicate the repeated phrase in the output.
            - Preserve the speaker's original intent, tone, and register (formal, casual, urgent,
              etc.) rather than flattening it.
        """.trimIndent()
    }

    private fun buildTextPrompt(text: String, languagePair: LanguagePair): String {
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

    private fun cleanTranslation(rawText: String): String {
        var text = rawText.trim()

        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            val firstLineEnd = text.indexOf('\n')
            if (firstLineEnd != -1 && !text.substring(0, firstLineEnd).contains(' ')) {
                text = text.substring(firstLineEnd + 1)
            }
            text = text.removeSuffix("```").trim()
        }

        LEADING_LABEL_REGEX.find(text)?.let { match ->
            text = text.substring(match.range.last + 1).trim()
        }

        return text
    }

    private companion object {
        const val TAG = "SigmaBridge"
        const val MODEL = "gemini-3.6-flash"
        const val STATE_ACTIVE = "ACTIVE"

        const val ACTIVE_POLL_INTERVAL_MS = 1_000L
        const val ACTIVE_POLL_TIMEOUT_MS = 60_000L

        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_INTERNAL_SERVER_ERROR = 500
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val HTTP_GATEWAY_TIMEOUT = 504
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val MAX_RETRY_ATTEMPTS = 4
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L

        val LEADING_LABEL_REGEX = Regex("^(arabic|ar)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    }
}
