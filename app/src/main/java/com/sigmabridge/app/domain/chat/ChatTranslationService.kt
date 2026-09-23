package com.sigmabridge.app.domain.chat

import com.sigmabridge.app.data.chat.ChatCrypto
import com.sigmabridge.app.data.chat.ChatGeminiTranslationRepository
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatLanguagePreferences
import com.sigmabridge.app.data.chat.ChatTranslationRelayRepository
import com.sigmabridge.app.data.chat.SupabaseSessionManager
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.Language
import com.sigmabridge.app.domain.model.LanguagePair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private Chat translation facade.
 *
 * A device with local Chat Gemini keys is the primary translation worker.
 * A device without keys requests translation through Supabase and waits for
 * the encrypted result. Telegram has its own translation runtime and does not
 * use this class.
 */
@Singleton
class ChatTranslationService @Inject constructor(
    private val geminiRepository: ChatGeminiTranslationRepository,
    private val languagePreferences: ChatLanguagePreferences,
    private val relayRepository: ChatTranslationRelayRepository,
    private val crypto: ChatCrypto,
    private val sessionManager: SupabaseSessionManager,
    private val identity: ChatIdentity
) {
    fun targetLanguage(): Language = languagePreferences.getTargetLanguage()

    fun setTargetLanguage(code: String) {
        LanguageCatalog.findByCode(code)?.let(languagePreferences::setTargetLanguage)
    }

    suspend fun translateIncoming(
        text: String,
        clientMessageId: String,
        peerUserId: String,
        conversationId: String
    ): Result<String> =
        translateIncomingTo(
            text,
            clientMessageId,
            languagePreferences.getTargetLanguage(),
            conversationId
        )

    suspend fun translateIncomingTo(
        text: String,
        clientMessageId: String,
        target: Language,
        conversationId: String
    ): Result<String> {
        val sourceCode = detectSimpleLanguage(text)
            ?: return Result.success(text)

        if (sourceCode == target.code || target.code == LanguageCatalog.AUTO_DETECT.code) {
            return Result.success(text)
        }

        return if (geminiRepository.hasConfiguredKeys()) {
            translateLocally(text, sourceCode, target)
        } else {
            relayRepository.requestTranslation(clientMessageId, target.code).fold(
                onSuccess = {
                    relayRepository.awaitTranslation(
                        clientMessageId,
                        target.code,
                        conversationId
                    )
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    suspend fun translateOutgoing(text: String): Result<String> {
        val target = languagePreferences.getTargetLanguage()
        val sourceCode = detectSimpleLanguage(text) ?: return Result.success(text)
        if (sourceCode == target.code || target.code == LanguageCatalog.AUTO_DETECT.code) {
            return Result.success(text)
        }
        return if (geminiRepository.hasConfiguredKeys()) {
            translateLocally(text, sourceCode, target)
        } else {
            Result.success(text)
        }
    }

    /**
     * Primary-device worker. Role metadata and local key presence are both checked.
     * The Gemini keys themselves stay local to this installation.
     */
    suspend fun processPendingRemoteTranslationJobs() {
        // Local Gemini keys are the functional capability. The server role is metadata
        // and must not block an existing primary installation from translating.
        if (!geminiRepository.hasConfiguredKeys()) return

        val localUserId = sessionManager.ensureAuthenticatedSession()
            .getOrThrow()
            .user?.id
            ?: return

        relayRepository.claimJobsV2().forEach { job ->
            runCatching {
                val sourceText = crypto.decryptForAccountPair(
                    job.ciphertext,
                    localUserId,
                    job.peerUserId
                )
                val sourceCode = detectSimpleLanguage(sourceText)
                    ?: error("Unsupported source language")
                val target = LanguageCatalog.findByCode(job.targetLanguage)
                    ?: error("Unsupported target language")
                val translated = translateLocally(sourceText, sourceCode, target).getOrThrow()
                relayRepository.completeJob(
                    job.jobId,
                    translated,
                    job.conversationId
                ).getOrThrow()
            }.onFailure { error ->
                relayRepository.failJob(job.jobId, error)
            }
        }
    }

    private suspend fun translateLocally(
        text: String,
        sourceCode: String,
        target: Language
    ): Result<String> {
        val source = LanguageCatalog.findByCode(sourceCode) ?: return Result.success(text)
        return geminiRepository.translateText(
            text,
            LanguagePair(source = source, target = target)
        )
    }

    private fun detectSimpleLanguage(text: String): String? {
        var arabic = 0
        var cyrillic = 0
        var latin = 0

        text.forEach { ch ->
            when {
                ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' || ch in 'ࢠ'..'ࣿ' -> arabic++
                ch in 'Ѐ'..'ӿ' -> cyrillic++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }

        val total = arabic + cyrillic + latin
        if (total == 0) return null

        return when {
            arabic > cyrillic && arabic > latin -> "ar"
            cyrillic > arabic && cyrillic > latin -> "ru"
            latin > arabic && latin > cyrillic -> "en"
            else -> null
        }
    }
}
