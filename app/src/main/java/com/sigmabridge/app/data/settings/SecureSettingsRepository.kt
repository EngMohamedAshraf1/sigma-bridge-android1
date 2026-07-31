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
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE_NAME = "sigma_bridge_secure_prefs"
private const val KEY_BOT_TOKEN = "bot_token"
private const val KEY_GEMINI_API_KEY = "gemini_api_key"

/**
 * Stores BOT_TOKEN and GEMINI_API_KEY in EncryptedSharedPreferences, whose
 * AES256-GCM data-encryption key is itself wrapped by a key that lives in
 * the Android Keystore (MasterKey.Builder default scheme). The keystore key
 * never leaves secure hardware/TEE when the device supports it, so the
 * secrets on disk are never plaintext even if the app's private storage is
 * extracted on a rooted device.
 *
 * This is the direct replacement for config.py's `os.environ.get(...)` +
 * `.env` file — same two values, but backed by a real secure store instead
 * of a plaintext file, since a phone (unlike a server the user controls) is
 * a device other apps and, if lost, other people can get physical access to.
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
    override val geminiApiKey: Flow<String?> = observeKey(KEY_GEMINI_API_KEY)

    override val hasCredentials: Flow<Boolean> =
        combine(botToken, geminiApiKey) { token, key ->
            !token.isNullOrBlank() && !key.isNullOrBlank()
        }

    override suspend fun saveBotToken(token: String) {
        prefs.edit().putString(KEY_BOT_TOKEN, token).apply()
    }

    override suspend fun saveGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key).apply()
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
