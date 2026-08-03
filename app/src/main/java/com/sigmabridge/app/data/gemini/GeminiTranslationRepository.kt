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
 * Mirrors services/gemini_service.py step for step: upload the raw audio
 * file (no local transcoding), poll until the file reaches ACTIVE, make a
 * single generateContent call that transcribes AND translates in one shot
 * (no separate STT pass), clean the response text, then delete the remote
 * file whether the call succeeded or not. That whole sequence lives in
 * [translateWithKey], completely unchanged by Phase 8.4 — multi-key support
 * is purely an outer loop in [translate] deciding *which key* to pass in.
 *
 * Phase 8.3 added [health] as an observability side-effect around this
 * sequence — BUSY while translate() is running, then READY or a specific
 * failure classification once it finishes.
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
     * deterministic round-robin order — never random, per the Phase 8.4
     * requirement. Bounded by the total configured key count fixed at the
     * start of this call, so it always terminates even if every key comes
     * back 429/401 (no infinite loop). A 429 just moves on to the next key;
     * a 401/403 additionally marks that key invalid for the rest of the
     * process. Any other failure (network error, malformed response, an
     * exhausted 503-retry inside translateWithKey) is not key-specific, so
     * it propagates immediately instead of cycling through remaining keys.
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

    /** The exact single-key sequence that existed before Phase 8.4 — untouched. */
    private suspend fun translateWithKey(apiKey: String, request: TranslationRequest): TranslationResult {
        var uploadedFile: GeminiFileDto? = null
        try {
            uploadedFile = apiClient.uploadFile(
                apiKey = apiKey,
                sourceFilePath = request.sourceFile.path,
                mimeType = AUDIO_MIME_TYPE,
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
                    mimeType = AUDIO_MIME_TYPE
                )
            }

            return TranslationResult(translatedText = cleanTranslation(rawText))
        } finally {
            // Best-effort remote cleanup, same as the Python finally block — never lets a
            // cleanup failure hide (or override) the actual translation result/error.
            uploadedFile?.let { runCatching { apiClient.deleteFile(apiKey, it.name) } }
        }
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

    /**
     * Same retry philosophy as gemini_service.py: only 503 (transient server
     * overload) is retried, with exponential backoff starting at 2s, capped
     * at [MAX_RETRY_ATTEMPTS] attempts. Any other failure — 4xx, malformed
     * response, network error — propagates immediately (up to translate()'s
     * key-cycling loop, as of Phase 8.4).
     */
    private suspend fun <T> withRetryOnTransientFailure(block: suspend () -> T): T {
        var attempt = 0
        var backoffMillis = INITIAL_BACKOFF_MS
        while (true) {
            try {
                return block()
            } catch (error: GeminiApiException) {
                attempt++
                if (error.httpCode != HTTP_SERVICE_UNAVAILABLE || attempt >= MAX_RETRY_ATTEMPTS) {
                    throw error
                }
                logger.debug(TAG, "Gemini 503, retrying attempt $attempt/$MAX_RETRY_ATTEMPTS in ${backoffMillis}ms")
                delay(backoffMillis)
                backoffMillis *= 2
            }
        }
    }

    /**
     * One request, one direction, no intermediate STT step — the model is
     * asked to listen to the audio and output only the translation, exactly
     * as gemini_service.py's prompt did for Russian -> Arabic. Phase 8.2
     * expanded the instructions themselves (translation quality only —
     * still one call, still output-only, still no markdown) to address:
     * naturalness over literalism, proper-noun/URL/phone-number/number
     * preservation, emoji/formatting preservation, natural punctuation and
     * sentence breaks, no duplicated phrases from stutters/restarts, and
     * preserving the speaker's tone/register.
     */
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

    /** Strips a wrapping code fence and a leading language-label prefix, same intent as _clean() in gemini_service.py. */
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
        const val AUDIO_MIME_TYPE = "audio/ogg"
        const val STATE_ACTIVE = "ACTIVE"

        const val ACTIVE_POLL_INTERVAL_MS = 1_000L
        const val ACTIVE_POLL_TIMEOUT_MS = 60_000L

        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val MAX_RETRY_ATTEMPTS = 4
        const val INITIAL_BACKOFF_MS = 2_000L

        val LEADING_LABEL_REGEX = Regex("^(arabic|ar)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    }
}
