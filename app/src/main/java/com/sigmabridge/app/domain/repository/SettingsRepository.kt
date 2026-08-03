package com.sigmabridge.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Reads/writes the secrets the bridge needs (BOT_TOKEN, Gemini key pool).
 * The domain layer only knows this as "credentials in, credentials out" —
 * it has no idea the implementation uses EncryptedSharedPreferences backed
 * by Android Keystore. That's a data-layer detail (SecureSettingsRepository,
 * added in Phase 2) and could be swapped for DataStore+Keystore or anything
 * else later without touching this interface or its callers.
 */
interface SettingsRepository {
    val botToken: Flow<String?>

    /** The full ordered pool of configured Gemini keys, used by GeminiApiKeyManager for deterministic round-robin and by Settings to render one row per key. */
    val geminiApiKeys: Flow<List<String>>

    suspend fun saveBotToken(token: String)

    /**
     * Replaces the entire Gemini key pool with [keys] — de-duplicated,
     * blank entries dropped, order preserved as given. This is a full
     * replace (not append), needed so the Settings UI can add/edit/delete
     * individual key slots and have Save commit the exact resulting list.
     * Order matters: GeminiApiKeyManager's round-robin depends on it.
     */
    suspend fun saveGeminiApiKeys(keys: List<String>)

    /** True once a bot token and at least one Gemini key have been saved (used to gate starting the bridge later). */
    val hasCredentials: Flow<Boolean>
}
