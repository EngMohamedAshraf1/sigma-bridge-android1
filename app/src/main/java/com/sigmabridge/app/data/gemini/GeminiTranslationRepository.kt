package com.sigmabridge.app.data.gemini

import com.sigmabridge.app.data.gemini.dto.GeminiFileDto
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.model.TranslationResult
import com.sigmabridge.app.domain.repository.SettingsRepository
import com.sigmabridge.app.domain.repository.TranslationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * file whether the call succeeded or not.
 */
@Singleton
class GeminiTranslationRepository @Inject constructor(
    private val apiClient: GeminiApiClient,
    private val settingsRepository: SettingsRepository,
    private val logger: BridgeLogger
) : TranslationRepository {

    override suspend fun translate(request: TranslationRequest): Result<TranslationResult> = runCatching {
        val apiKey = settingsRepository.geminiApiKey.first()
            ?: error("Cannot translate: Gemini API key not set")

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

            TranslationResult(translatedText = cleanTranslation(rawText))
        } finally {
            // Best-effort remote cleanup, same as the Python finally block — never lets a
            // cleanup failure hide (or override) the actual translation result/error.
            uploadedFile?.let { runCatching { apiClient.deleteFile(apiKey, it.name) } }
        }
    }.onFailure { error ->
        logger.error(TAG, "Translation failed for ${request.sourceFile.id}", error)
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
     * response, network error — propagates immediately.
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
     * as gemini_service.py's prompt did for Russian -> Arabic.
     */
    private fun buildPrompt(languagePair: LanguagePair): String =
        "You are a direct, real-time voice translator. Listen to the attached audio in " +
            "${languagePair.source.displayName} and output ONLY its translation in " +
            "${languagePair.target.displayName}. Do not transcribe or repeat the source language. " +
            "Do not add explanations, notes, or any text other than the translation itself. " +
            "Do not use markdown formatting."

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
        const val MAX_RETRY_ATTEMPTS = 4
        const val INITIAL_BACKOFF_MS = 2_000L

        val LEADING_LABEL_REGEX = Regex("^(arabic|ar)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    }
}
