package com.sigmabridge.app.data.gemini

import com.sigmabridge.app.data.gemini.dto.GeminiFileDto
import com.sigmabridge.app.domain.gemini.GeminiApiKeyManager
import com.sigmabridge.app.domain.gemini.NoAvailableGeminiKeyException
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.GeminiHealth
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TemporaryImageFile
import com.sigmabridge.app.domain.repository.ImageTranslationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/** Independent Gemini image translation path. */
@Singleton
class GeminiImageTranslationRepository @Inject constructor(
    private val apiClient: GeminiApiClient,
    private val keyManager: GeminiApiKeyManager,
    private val logger: BridgeLogger
) : ImageTranslationRepository {

    private val _health = MutableStateFlow(GeminiHealth.UNKNOWN)
    override val health: StateFlow<GeminiHealth> = _health.asStateFlow()

    override suspend fun translate(
        image: TemporaryImageFile,
        languagePair: LanguagePair
    ): Result<String> = runCatching {
        _health.value = GeminiHealth.BUSY

        val file = File(image.path)
        require(file.exists() && file.isFile) {
            "Image file does not exist: " + image.path
        }

        val totalKeys = keyManager.totalKeyCount()
        if (totalKeys == 0) {
            throw NoAvailableGeminiKeyException("No Gemini API key configured")
        }

        val prompt = buildPrompt(languagePair)
        var lastError: Throwable = NoAvailableGeminiKeyException(
            "All configured Gemini API keys are unavailable"
        )
        var attempt = 0

        while (attempt < totalKeys) {
            val apiKey = keyManager.nextKey() ?: break
            attempt++

            try {
                val raw = translateWithKey(
                    apiKey = apiKey,
                    file = file,
                    image = image,
                    prompt = prompt
                )
                keyManager.markSucceeded(apiKey)
                _health.value = GeminiHealth.READY
                return@runCatching cleanTranslation(raw)
            } catch (error: IOException) {
                lastError = error
                logger.error(TAG, "Image translation network failure; trying next key", error)
            } catch (error: GeminiApiException) {
                lastError = error
                when (error.httpCode) {
                    HTTP_TOO_MANY_REQUESTS -> keyManager.markQuotaExceeded(apiKey)
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> keyManager.markInvalid(apiKey)
                    else -> {
                        if (!isTransient(error)) throw error
                        logger.error(TAG, "Transient Gemini image error; trying next key", error)
                    }
                }
            }
        }

        throw lastError
    }.onFailure { result ->
        val error = result.exceptionOrNull()
        _health.value = when {
            error is GeminiApiException && error.httpCode == HTTP_TOO_MANY_REQUESTS -> GeminiHealth.QUOTA_EXCEEDED
            error is GeminiApiException && (error.httpCode == HTTP_UNAUTHORIZED || error.httpCode == HTTP_FORBIDDEN) -> GeminiHealth.AUTHENTICATION_FAILED
            else -> GeminiHealth.NETWORK_ERROR
        }
    }

    private suspend fun translateWithKey(
        apiKey: String,
        file: File,
        image: TemporaryImageFile,
        prompt: String
    ): String {
        var uploadedFile: GeminiFileDto? = null

        return try {
            uploadedFile = withRetryOnTransientFailure {
                apiClient.uploadFile(
                    apiKey = apiKey,
                    sourceFilePath = file.path,
                    mimeType = image.mimeType,
                    displayName = image.id
                )
            }

            val activeFile = awaitActiveState(apiKey, uploadedFile)
            val fileUri = activeFile.uri
                ?: error("Gemini image file has no uri after becoming ACTIVE.")

            withRetryOnTransientFailure {
                apiClient.generateContent(
                    apiKey = apiKey,
                    model = MODEL,
                    prompt = prompt,
                    fileUri = fileUri,
                    mimeType = image.mimeType
                )
            }
        } finally {
            uploadedFile?.let { uploaded ->
                runCatching { apiClient.deleteFile(apiKey, uploaded.name) }
            }
        }
    }

    private suspend fun awaitActiveState(
        apiKey: String,
        file: GeminiFileDto
    ): GeminiFileDto {
        var current = file
        var waitedMillis = 0L

        while (current.state != STATE_ACTIVE) {
            if (current.state == STATE_FAILED) {
                error("Gemini image file processing failed for " + file.name)
            }
            if (waitedMillis >= ACTIVE_POLL_TIMEOUT_MS) {
                error("Gemini image file did not become ACTIVE within " + ACTIVE_POLL_TIMEOUT_MS + "ms")
            }

            delay(ACTIVE_POLL_INTERVAL_MS)
            waitedMillis += ACTIVE_POLL_INTERVAL_MS
            val currentFileName = current.name
            current = withRetryOnTransientFailure {
                apiClient.getFile(apiKey, currentFileName)
            }
        }
        return current
    }

    private suspend fun <T> withRetryOnTransientFailure(
        block: suspend () -> T
    ): T {
        var attempt = 0
        var backoffMillis = INITIAL_BACKOFF_MS
        while (true) {
            try {
                return block()
            } catch (error: IOException) {
                attempt++
                if (attempt >= MAX_RETRY_ATTEMPTS) throw error
                delay(backoffMillis)
                backoffMillis = minOf(backoffMillis * 2, MAX_BACKOFF_MS)
            } catch (error: GeminiApiException) {
                attempt++
                if (!isTransient(error) || attempt >= MAX_RETRY_ATTEMPTS) throw error
                val jitter = Random.nextLong(0L, RETRY_JITTER_MAX_MS + 1L)
                delay(backoffMillis + jitter)
                backoffMillis = minOf(backoffMillis * 2, MAX_BACKOFF_MS)
            }
        }
    }

    private fun isTransient(error: GeminiApiException): Boolean =
        error.httpCode == HTTP_REQUEST_TIMEOUT ||
            error.httpCode == HTTP_INTERNAL_SERVER_ERROR ||
            error.httpCode == HTTP_SERVICE_UNAVAILABLE ||
            error.httpCode == HTTP_GATEWAY_TIMEOUT

    private fun buildPrompt(languagePair: LanguagePair): String =
        """
        Read all clearly visible text in this image and translate it from
        ${languagePair.source.displayName} to natural, fluent ${languagePair.target.displayName}.

        Rules:
        - Extract the text in normal reading order.
        - Translate the meaning naturally; do not summarize.
        - Output ONLY the translated text.
        - Do not describe the image.
        - Preserve names, URLs, email addresses, phone numbers, numbers, emojis, and symbols.
        - Keep separate text blocks separated by line breaks when that helps preserve the original structure.
        - Ignore decorative elements that are not text.
        """.trimIndent()

    private fun cleanTranslation(raw: String): String = raw.trim()

    private companion object {
        const val TAG = "SigmaBridge"
        const val MODEL = "gemini-3.6-flash"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_FAILED = "FAILED"
        const val ACTIVE_POLL_INTERVAL_MS = 1_000L
        const val ACTIVE_POLL_TIMEOUT_MS = 60_000L
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_INTERNAL_SERVER_ERROR = 500
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val HTTP_GATEWAY_TIMEOUT = 504
        const val MAX_RETRY_ATTEMPTS = 4
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val RETRY_JITTER_MAX_MS = 500L
    }
}
