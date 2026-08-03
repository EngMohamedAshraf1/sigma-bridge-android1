package com.sigmabridge.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sigmabridge.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE_NAME = "sigma_bridge_secure_prefs"
private const val KEY_BOT_TOKEN = "bot_token"
private const val KEY_GEMINI_API_KEY = "gemini_api_key" // legacy pre-8.4 single-key slot, read-only migration source now
private const val KEY_GEMINI_API_KEYS = "gemini_api_keys" // ordered pool, newline-delimited
private const val KEY_DELIMITER = "\n"

/**
 * Stores BOT_TOKEN and the Gemini key pool in EncryptedSharedPreferences,
 * whose AES256-GCM data-encryption key is itself wrapped by a key that
 * lives in the Android Keystore (MasterKey.Builder default scheme). The
 * keystore key never leaves secure hardware/TEE when the device supports
 * it, so the secrets on disk are never plaintext even if the app's private
 * storage is extracted on a rooted device.
 *
 * The key pool is stored as one newline-delimited string (not a String Set)
 * because SharedPreferences' StringSet does not guarantee iteration order,
 * and GeminiApiKeyManager's round-robin must be deterministic. API keys
 * only ever contain [A-Za-z0-9._-] per SaveSettingsUseCase's validation, so
 * none can contain a literal newline — no escaping is needed.
 */
@Singleton
class SecureSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : SettingsRepository {

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

    override val botToken: Flow<String?> = observeKey(KEY_BOT_TOKEN)

    override val geminiApiKeys: Flow<List<String>> = observeKey(KEY_GEMINI_API_KEYS).map { raw ->
        val storedList = raw?.split(KEY_DELIMITER)?.filter { it.isNotBlank() }.orEmpty()
        if (storedList.isNotEmpty()) {
            storedList
        } else {
            // Backward compatibility: a user who saved a key before the multi-key pool
            // existed has it only in the legacy single-key slot. Surface it as a
            // one-item pool instead of silently losing it, without forcing a
            // write-back on every read.
            val legacyKey = prefs.getString(KEY_GEMINI_API_KEY, null)
            if (legacyKey.isNullOrBlank()) emptyList() else listOf(legacyKey)
        }
    }

    override val hasCredentials: Flow<Boolean> =
        combine(botToken, geminiApiKeys) { token, keys ->
            !token.isNullOrBlank() && keys.isNotEmpty()
        }

    override suspend fun saveBotToken(token: String) {
        prefs.edit().putString(KEY_BOT_TOKEN, token).apply()
    }

    override suspend fun saveGeminiApiKeys(keys: List<String>) {
        // LinkedHashSet: de-duplicates while preserving the first occurrence's
        // position, so the stored order (and therefore round-robin order) matches
        // what the user sees in the Settings list, minus any duplicate entries.
        val deduped = LinkedHashSet<String>()
        keys.forEach { key ->
            val trimmed = key.trim()
            if (trimmed.isNotEmpty()) deduped += trimmed
        }
        prefs.edit()
            .putString(KEY_GEMINI_API_KEYS, deduped.joinToString(KEY_DELIMITER))
            .apply()
    }

    /** Emits the current value immediately, then again on every change to [key]. */
    private fun observeKey(key: String): Flow<String?> = callbackFlow {
        trySend(prefs.getString(key, null))

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                trySend(changedPrefs.getString(key, null))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
