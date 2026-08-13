package com.sigmabridge.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.Language
import com.sigmabridge.app.domain.model.LanguageConfiguration
import com.sigmabridge.app.domain.repository.LanguagePreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE_NAME = "sigma_bridge_language_prefs"
private const val KEY_GLOBAL_SOURCE = "language_global_source"
private const val KEY_GLOBAL_TARGET = "language_global_target"

/**
 * Same construction as SecureSettingsRepository (EncryptedSharedPreferences
 * backed by an Android Keystore MasterKey, AES256_GCM scheme) — but its own
 * prefs file, since language preferences are a different, independently-
 * evolving concern from bot/Gemini credentials, not because the technique
 * differs at all.
 *
 * Only the language *code* (e.g. "ru") is stored per scope, not the whole
 * Language object — displayName is re-derived from LanguageCatalog on
 * read, falling back to Language(code, code) for a code that catalog
 * doesn't (yet) recognize, so a future catalog addition can never make an
 * old stored preference unreadable.
 *
 * Phase 9.7: "user" preferences moved from a single userId-keyed slot to a
 * (chatId, userId)-keyed slot, so the same person can have a different
 * language per chat. The old userId-only slot is never deleted or
 * overwritten by this class again — getUser() falls back to reading it
 * (once per chat, on first access) as the seed value for any chat that
 * hasn't been explicitly configured yet, and immediately writes that value
 * forward into the new composite key for that chat so future reads for
 * this exact chat skip the fallback. This means an existing single-group
 * user sees zero behavior change, and every new chat they interact with
 * starts from their old preference as a reasonable default, independently
 * customizable from there without touching any other chat.
 */
@Singleton
class SecureLanguagePreferencesRepository @Inject constructor(
    @ApplicationContext context: Context
) : LanguagePreferencesRepository {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override suspend fun getGlobal(): LanguageConfiguration =
        readConfiguration(KEY_GLOBAL_SOURCE, KEY_GLOBAL_TARGET)

    override suspend fun setGlobal(configuration: LanguageConfiguration) {
        writeConfiguration(KEY_GLOBAL_SOURCE, KEY_GLOBAL_TARGET, configuration)
    }

    override suspend fun getChat(chatId: Long): LanguageConfiguration =
        readConfiguration(chatSourceKey(chatId), chatTargetKey(chatId))

    override suspend fun setChat(chatId: Long, configuration: LanguageConfiguration) {
        writeConfiguration(chatSourceKey(chatId), chatTargetKey(chatId), configuration)
    }

    override suspend fun getUser(chatId: Long, userId: Long): LanguageConfiguration {
        val composite = readConfiguration(userSourceKey(chatId, userId), userTargetKey(chatId, userId))
        if (composite != LanguageConfiguration.DEFAULT) {
            return composite
        }

        // Nothing set for this exact chat yet - fall back to the pre-9.7 legacy
        // userId-only slot, if the user configured one before chats were tracked.
        val legacy = readConfiguration(legacyUserSourceKey(userId), legacyUserTargetKey(userId))
        if (legacy != LanguageConfiguration.DEFAULT) {
            // Migrate: write it forward for THIS chat so it no longer needs the
            // fallback. The legacy key itself is left untouched - it remains the
            // seed for any other chat this user hasn't customized yet.
            writeConfiguration(userSourceKey(chatId, userId), userTargetKey(chatId, userId), legacy)
            return legacy
        }

        return LanguageConfiguration.DEFAULT
    }

    override suspend fun setUser(chatId: Long, userId: Long, configuration: LanguageConfiguration) {
        writeConfiguration(userSourceKey(chatId, userId), userTargetKey(chatId, userId), configuration)
    }

    private fun readConfiguration(sourceKey: String, targetKey: String): LanguageConfiguration {
        val sourceCode = prefs.getString(sourceKey, null)
        val targetCode = prefs.getString(targetKey, null)
        if (sourceCode == null || targetCode == null) {
            // Nothing configured for this exact scope yet - transparent fallback.
            return LanguageConfiguration.DEFAULT
        }
        return LanguageConfiguration(source = codeToLanguage(sourceCode), target = codeToLanguage(targetCode))
    }

    private fun writeConfiguration(sourceKey: String, targetKey: String, configuration: LanguageConfiguration) {
        prefs.edit()
            .putString(sourceKey, configuration.source.code)
            .putString(targetKey, configuration.target.code)
            .apply()
    }

    private fun codeToLanguage(code: String): Language =
        LanguageCatalog.findByCode(code) ?: Language(code = code, displayName = code)

    private fun chatSourceKey(chatId: Long) = "language_chat_${chatId}_source"
    private fun chatTargetKey(chatId: Long) = "language_chat_${chatId}_target"
    private fun userSourceKey(chatId: Long, userId: Long) = "language_user_${chatId}_${userId}_source"
    private fun userTargetKey(chatId: Long, userId: Long) = "language_user_${chatId}_${userId}_target"
    private fun legacyUserSourceKey(userId: Long) = "language_user_${userId}_source"
    private fun legacyUserTargetKey(userId: Long) = "language_user_${userId}_target"
}
