package com.sigmabridge.app.data.gemini

import com.sigmabridge.app.domain.gemini.GeminiApiKeyManager
import com.sigmabridge.app.domain.gemini.NoAvailableGeminiKeyException
import com.sigmabridge.app.domain.logging.BridgeLogger
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TemporaryImageFile
import com.sigmabridge.app.domain.repository.ImageTranslationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Independent Gemini image translation path. Audio/video translation code is not used here. */
@Singleton
class GeminiImageTranslationRepository @Inject constructor(
    private val apiClient: GeminiApiClient,
    private val keyManager: GeminiApiKeyManager,
    private val logger: BridgeLogger
) : ImageTranslationRepository {

    override suspend fun translate(
        image: TemporaryImageFile,
        languagePair: LanguagePair
    ): Result<String> = runCatching {
        val file = File(image.path)
        require(file.exists() && file.isFile) {
            "Image file does not exist: ${image.path}"
        }
        require(file.length() <= MAX_INLINE_IMAGE_BYTES) {
            "Image is too large for inline Gemini translation."
        }

        val data = withContext(Dispatchers.IO) { file.readBytes() }
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
                val raw = apiClient.generateContentInlineImage(
                    apiKey = apiKey,
                    model = MODEL,
                    prompt = prompt,
                    mimeType = image.mimeType,
                    data = data
                )
                keyManager.markSucceeded(apiKey)
                return@runCatching cleanTranslation(raw)
            } catch (error: GeminiApiException) {
                lastError = error
                when (error.httpCode) {
                    HTTP_TOO_MANY_REQUESTS -> keyManager.markQuotaExceeded(apiKey)
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> keyManager.markInvalid(apiKey)
                    HTTP_BAD_REQUEST, HTTP_NOT_FOUND -> throw error
                    else -> logger.error(
                        TAG,
                        "Image translation Gemini request failed; trying next key",
                        error
                    )
                }
            }
        }

        throw lastError
    }

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

    private fun cleanTranslation(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            val firstLineEnd = text.indexOf('\n')
            if (firstLineEnd >= 0) {
                text = text.substring(firstLineEnd + 1)
            }
            text = text.removeSuffix("```").trim()
        }
        return text
    }

    private companion object {
        const val TAG = "SigmaBridge"
        const val MODEL = "gemini-3.6-flash"
        const val MAX_INLINE_IMAGE_BYTES = 15_000_000L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}
