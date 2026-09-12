package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AlternativeTranslationRequest(
    val text: String,
    @SerialName("targetLanguage") val targetLanguage: String
)

@Serializable
data class AlternativeTranslationResponse(
    val translatedText: String,
    val targetLanguage: String
)

/**
 * Private Chat fallback translation path.
 *
 * This path is independent from the primary phone's local Gemini worker.
 * The Google credential stays inside the Supabase Edge Function.
 * Telegram never uses this repository.
 */
@Singleton
class ChatAlternativeTranslationRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    suspend fun translate(text: String, targetLanguage: String): Result<String> = runCatching {
        withTimeout(30_000L) {
            val response = supabase.functions.invoke(
                function = "google-alternative-translation",
                body = AlternativeTranslationRequest(
                    text = text,
                    targetLanguage = targetLanguage
                )
            )
            response.body<AlternativeTranslationResponse>().translatedText.trim()
                .takeIf { it.isNotBlank() }
                ?: error("EMPTY_TRANSLATION")
        }
    }
}
