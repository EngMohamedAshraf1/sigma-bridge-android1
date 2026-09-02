package com.sigmabridge.app.data.gemini.dto

import kotlinx.serialization.Serializable

// --- Files API (upload / poll / delete) ---

@Serializable
data class GeminiFileUploadMetadataDto(
    val file: GeminiFileMetadataDto
)

@Serializable
data class GeminiFileMetadataDto(
    val displayName: String
)

/** Also used directly as the response body of the poll ("get file") call. */
@Serializable
data class GeminiFileDto(
    val name: String,
    val uri: String? = null,
    val mimeType: String? = null,
    val state: String? = null
)

@Serializable
data class GeminiFileResponseWrapperDto(
    val file: GeminiFileDto
)

// --- generateContent ---

@Serializable
data class GeminiGenerateContentRequestDto(
    val contents: List<GeminiContentDto>,
    val generationConfig: GeminiGenerationConfigDto? = null
)

@Serializable
data class GeminiGenerationConfigDto(
    val thinkingConfig: GeminiThinkingConfigDto? = null
)

@Serializable
data class GeminiThinkingConfigDto(
    val thinkingLevel: String? = null
)

@Serializable
data class GeminiContentDto(
    val parts: List<GeminiPartDto>
)

@Serializable
data class GeminiPartDto(
    val text: String? = null,
    val fileData: GeminiFileDataDto? = null
)

@Serializable
data class GeminiFileDataDto(
    val mimeType: String,
    val fileUri: String
)

@Serializable
data class GeminiGenerateContentResponseDto(
    val candidates: List<GeminiCandidateDto> = emptyList()
)

@Serializable
data class GeminiCandidateDto(
    val content: GeminiContentDto? = null
)
