package com.sigmabridge.app.data.gemini

import com.sigmabridge.app.data.gemini.dto.GeminiContentDto
import com.sigmabridge.app.data.gemini.dto.GeminiFileDataDto
import com.sigmabridge.app.data.gemini.dto.GeminiFileDto
import com.sigmabridge.app.data.gemini.dto.GeminiFileMetadataDto
import com.sigmabridge.app.data.gemini.dto.GeminiFileResponseWrapperDto
import com.sigmabridge.app.data.gemini.dto.GeminiFileUploadMetadataDto
import com.sigmabridge.app.data.gemini.dto.GeminiGenerateContentRequestDto
import com.sigmabridge.app.data.gemini.dto.GeminiGenerateContentResponseDto
import com.sigmabridge.app.data.gemini.dto.GeminiGenerationConfigDto
import com.sigmabridge.app.data.gemini.dto.GeminiPartDto
import com.sigmabridge.app.data.gemini.dto.GeminiThinkingConfigDto
import com.sigmabridge.app.data.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * No google-genai (or any other) SDK dependency — every call here is a
 * plain REST request built with OkHttp + kotlinx.serialization, the same
 * stack already used for Telegram in Phase 3/4. [httpCode] is attached to
 * every thrown exception so GeminiTranslationRepository can decide
 * retryability without re-parsing anything.
 */
class GeminiApiException(message: String, val httpCode: Int? = null) : Exception(message)

class GeminiApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    /** Step 1+2 of Google's resumable upload protocol, collapsed into one suspend call. */
    suspend fun uploadFile(apiKey: String, sourceFilePath: String, mimeType: String, displayName: String): GeminiFileDto =
        withContext(Dispatchers.IO) {
            val file = File(sourceFilePath)

            val startUrl = "$BASE_URL/upload/v1beta/files".toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .build()
            val metadataBody = json.encodeToString(
                GeminiFileUploadMetadataDto.serializer(),
                GeminiFileUploadMetadataDto(GeminiFileMetadataDto(displayName = displayName))
            ).toRequestBody("application/json".toMediaType())

            val startRequest = Request.Builder()
                .url(startUrl)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .post(metadataBody)
                .build()

            val uploadUrl = httpClient.newCall(startRequest).await().use { response ->
                if (!response.isSuccessful) {
                    throw GeminiApiException("Gemini upload session start failed: HTTP ${response.code}", response.code)
                }
                response.header("X-Goog-Upload-URL")
                    ?: throw GeminiApiException("Gemini upload session did not return an upload URL")
            }

            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .post(file.readBytes().toRequestBody(mimeType.toMediaType()))
                .build()

            httpClient.newCall(uploadRequest).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GeminiApiException("Gemini file upload failed: HTTP ${response.code} — $body", response.code)
                }
                json.decodeFromString(GeminiFileResponseWrapperDto.serializer(), body).file
            }
        }

    suspend fun getFile(apiKey: String, fileName: String): GeminiFileDto = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/v1beta/$fileName".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()

        httpClient.newCall(Request.Builder().url(url).build()).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiApiException("Gemini getFile failed: HTTP ${response.code} — $body", response.code)
            }
            json.decodeFromString(GeminiFileDto.serializer(), body)
        }
    }

    suspend fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        fileUri: String,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/v1beta/models/$model:generateContent".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()

        val requestDto = GeminiGenerateContentRequestDto(
            contents = listOf(
                GeminiContentDto(
                    parts = listOf(
                        GeminiPartDto(text = prompt),
                        GeminiPartDto(fileData = GeminiFileDataDto(mimeType = mimeType, fileUri = fileUri))
                    )
                )
            )
        )
        val requestBody = json.encodeToString(GeminiGenerateContentRequestDto.serializer(), requestDto)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody).build()

        httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiApiException("Gemini generateContent failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(GeminiGenerateContentResponseDto.serializer(), body)
            parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull { it.text != null }?.text
                ?: throw GeminiApiException("Gemini generateContent returned no text")
        }
    }

    /** Fast text generation reserved for Private Chat. Telegram's existing model/path is untouched. */
    suspend fun generateTextContent(
        apiKey: String,
        model: String,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/v1beta/models/$model:generateContent".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()

        val requestDto = GeminiGenerateContentRequestDto(
            contents = listOf(
                GeminiContentDto(
                    parts = listOf(GeminiPartDto(text = prompt))
                )
            ),
            generationConfig = GeminiGenerationConfigDto(
                thinkingConfig = GeminiThinkingConfigDto(thinkingLevel = "minimal")
            )
        )
        val requestBody = json.encodeToString(GeminiGenerateContentRequestDto.serializer(), requestDto)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody).build()
        val call = httpClient.newCall(request).apply {
            timeout().timeout(CHAT_TEXT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        call.await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiApiException("Gemini generateTextContent failed: HTTP ${response.code} — $body", response.code)
            }
            val parsed = json.decodeFromString(GeminiGenerateContentResponseDto.serializer(), body)
            parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull { it.text != null }?.text
                ?: throw GeminiApiException("Gemini generateTextContent returned no text")
        }
    }

    /** Best-effort cleanup — failures here are swallowed by the caller, same as the Python version's finally block. */
    suspend fun deleteFile(apiKey: String, fileName: String) = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/v1beta/$fileName".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        httpClient.newCall(Request.Builder().url(url).delete().build()).await().close()
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        const val CHAT_TEXT_REQUEST_TIMEOUT_MS = 12_000L
    }
}
