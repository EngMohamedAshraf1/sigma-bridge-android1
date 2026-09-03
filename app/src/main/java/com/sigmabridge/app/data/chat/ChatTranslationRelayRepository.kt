package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TranslationJobRpcResult(
    val jobId: String,
    val clientMessageId: String,
    val targetLanguage: String,
    val ciphertext: String,
    val nonce: String,
    val messageVersion: Int
)

@Serializable
data class TranslationResultRpcRow(
    val status: String,
    val translatedCiphertext: String? = null,
    val translatedNonce: String? = null,
    val lastError: String? = null
)

/**
 * Supabase relay for primary-device translation.
 * No plaintext is stored here: requests contain identifiers only and the
 * translated result is encrypted before it is written back by the primary.
 */
@Singleton
class ChatTranslationRelayRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val crypto: ChatCrypto
) {
    suspend fun requestTranslation(clientMessageId: String, targetLanguage: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc(
            "sigma_request_translation",
            mapOf(
                "p_client_message_id" to clientMessageId,
                "p_target_language" to targetLanguage
            )
        )
    }

    suspend fun awaitTranslation(clientMessageId: String, targetLanguage: String): Result<String> = runCatching {
        withTimeout(30_000L) {
            while (true) {
                val result = supabase.postgrest.rpc(
                    "sigma_get_translation",
                    mapOf(
                        "p_client_message_id" to clientMessageId,
                        "p_target_language" to targetLanguage
                    )
                ).decodeList<TranslationResultRpcRow>().firstOrNull()

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
            .decodeList<TranslationJobRpcResult>()

    suspend fun completeJob(
        jobId: String,
        translatedText: String
    ): Result<Unit> = runCatching {
        val encrypted = crypto.encrypt(translatedText)
        supabase.postgrest.rpc(
            "sigma_complete_translation_job",
            mapOf(
                "p_job_id" to jobId,
                "p_translated_ciphertext" to encrypted,
                "p_translated_nonce" to crypto.nonceFromEncrypted(encrypted)
            )
        )
    }

    suspend fun failJob(jobId: String, error: Throwable): Result<Unit> = runCatching {
        supabase.postgrest.rpc(
            "sigma_fail_translation_job",
            mapOf(
                "p_job_id" to jobId,
                "p_error" to "TRANSLATION_FAILED"
            )
        )
    }
}
