package com.sigmabridge.app.domain.chat

import com.sigmabridge.app.data.chat.ChatAlternativeTranslationRepository
import com.sigmabridge.app.data.chat.ChatCrypto
import com.sigmabridge.app.data.chat.ChatGeminiTranslationRepository
import com.sigmabridge.app.data.chat.ChatLanguagePreferences
import com.sigmabridge.app.data.chat.ChatTranslationRelayRepository
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
 * the encrypted result. Manual alternative translation uses a direct
 * Supabase Edge Function and does not depend on the primary phone.
 * Telegram has its own translation runtime and does not use this class.
 */
@Singleton
class ChatTranslationService @Inject constructor(
    private val geminiRepository: ChatGeminiTranslationRepository,
    private val languagePreferences: ChatLanguagePreferences,
    private val relayRepository: ChatTranslationRelayRepository,
    private val alternativeRepository: ChatAlternativeTranslationRepository,
    private val crypto: ChatCrypto
) {
    fun targetLanguage(): Language = languagePreferences.getTargetLanguage()

    fun setTargetLanguage(code: String) {
        LanguageCatalog.findByCode(code)?.let(languagePreferences::setTargetLanguage)
    }

    suspend fun translateIncoming(text: String, clientMessageId: String): Result<String> =
        translateIncomingTo(text, clientMessageId, languagePreferences.getTargetLanguage())

    /**
     * Translate using the existing primary Gemini/relay path.
     */
    suspend fun translateIncomingTo(
        text: String,
        clientMessageId: String,
        target: Language
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
                onSuccess = { relayRepository.awaitTranslation(clientMessageId, target.code) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Manual fallback path. This never uses the primary phone's Gemini worker.
     */
    suspend fun translateAlternative(text: String, target: Language): Result<String> {
        val sourceCode = detectSimpleLanguage(text)
            ?: return Result.success(text)

        if (sourceCode == target.code || target.code == LanguageCatalog.AUTO_DETECT.code) {
            return Result.success(text)
        }

        return alternativeRepository.translate(text, target.code)
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
     * Primary-device worker. Secondary devices return immediately because they
     * do not have local Chat Gemini keys.
     */
    suspend fun processPendingRemoteTranslationJobs() {
        if (!geminiRepository.hasConfiguredKeys()) return

        relayRepository.claimJobs().forEach { job ->
            runCatching {
                val sourceText = crypto.decrypt(job.ciphertext)
                val sourceCode = detectSimpleLanguage(sourceText)
                    ?: error("Unsupported source language")
                val target = LanguageCatalog.findByCode(job.targetLanguage)
                    ?: error("Unsupported target language")
                val translated = translateLocally(sourceText, sourceCode, target).getOrThrow()
                relayRepository.completeJob(job.jobId, translated).getOrThrow()
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

    /** Lightweight script detection for chat; no extra network/model call. */
    private fun detectSimpleLanguage(text: String): String? {
        var arabic = 0
        var cyrillic = 0
        var latin = 0

        text.forEach { ch ->
            when {
                ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF' -> arabic++
                ch in '\u0400'..'\u04FF' -> cyrillic++
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
