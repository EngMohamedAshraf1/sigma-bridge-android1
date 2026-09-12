package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
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
 * Server-side fallback for Private Chat translation.
 * The Google Gemini API key never ships in the Android app.
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
