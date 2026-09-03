package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TranslationRequestRpcParams(
    @SerialName("p_client_message_id") val clientMessageId: String,
    @SerialName("p_target_language") val targetLanguage: String
)

@Serializable
data class TranslationJobRpcParams(
    @SerialName("p_job_id") val jobId: String,
    @SerialName("p_translated_ciphertext") val translatedCiphertext: String,
    @SerialName("p_translated_nonce") val translatedNonce: String
)

@Serializable
data class TranslationJobErrorRpcParams(
    @SerialName("p_job_id") val jobId: String,
    @SerialName("p_error") val error: String
)

@Serializable
data class TranslationLookupRpcParams(
    @SerialName("p_client_message_id") val clientMessageId: String,
    @SerialName("p_target_language") val targetLanguage: String
)

@Serializable
data class TranslationJobRpcResult(
    @SerialName("job_id") val jobId: String,
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("target_language") val targetLanguage: String,
    @SerialName("ciphertext") val ciphertext: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("message_version") val messageVersion: Int
)

@Serializable
data class TranslationResultRpcRow(
    val status: String,
    @SerialName("translated_ciphertext") val translatedCiphertext: String? = null,
    @SerialName("translated_nonce") val translatedNonce: String? = null,
    @SerialName("last_error") val lastError: String? = null
)

/**
 * Relay used only by Private Chat's primary/secondary translation protocol.
 * Requests contain identifiers only; translated text is encrypted before it
 * is stored in Supabase. Telegram never uses this repository.
 */
@Singleton
class ChatTranslationRelayRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val crypto: ChatCrypto
) {
    suspend fun requestTranslation(clientMessageId: String, targetLanguage: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc(
            "sigma_request_translation",
            TranslationRequestRpcParams(clientMessageId, targetLanguage)
        )
    }

    suspend fun awaitTranslation(clientMessageId: String, targetLanguage: String): Result<String> = runCatching {
        withTimeout(30_000L) {
            while (true) {
                val result = supabase.postgrest.rpc(
                    "sigma_get_translation",
                    TranslationLookupRpcParams(clientMessageId, targetLanguage)
                ).decodeAs<List<TranslationResultRpcRow>>().firstOrNull()

                when (result?.status) {
                    "COMPLETED" -> {
                        val encrypted = result.translatedCiphertext
                            ?: error("Translation result is empty.")
                        return@withTimeout crypto.decrypt(encrypted)
                    }
                    "FAILED" -> error("Remote translation failed.")
                }

                delay(2_000L)
            }
        }
    }

    suspend fun claimJobs(): List<TranslationJobRpcResult> =
        supabase.postgrest.rpc("sigma_claim_translation_jobs")
            .decodeAs<List<TranslationJobRpcResult>>()

    suspend fun completeJob(jobId: String, translatedText: String): Result<Unit> = runCatching {
        val encrypted = crypto.encrypt(translatedText)
        supabase.postgrest.rpc(
            "sigma_complete_translation_job",
            TranslationJobRpcParams(
                jobId = jobId,
                translatedCiphertext = encrypted,
                translatedNonce = crypto.nonceFromEncrypted(encrypted)
            )
        )
    }

    suspend fun failJob(jobId: String, error: Throwable): Result<Unit> = runCatching {
        supabase.postgrest.rpc(
            "sigma_fail_translation_job",
            TranslationJobErrorRpcParams(jobId, "TRANSLATION_FAILED")
        )
    }
}
