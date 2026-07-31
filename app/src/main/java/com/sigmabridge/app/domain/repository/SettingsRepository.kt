package com.sigmabridge.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Reads/writes the two secrets the bridge needs (BOT_TOKEN, GEMINI_API_KEY).
 * The domain layer only knows this as "credentials in, credentials out" —
 * it has no idea the implementation uses EncryptedSharedPreferences backed
 * by Android Keystore. That's a data-layer detail (SecureSettingsRepository,
 * added in this same phase) and could be swapped for DataStore+Keystore or
 * anything else later without touching this interface or its callers.
 */
interface SettingsRepository {
    val botToken: Flow<String?>
    val geminiApiKey: Flow<String?>

    suspend fun saveBotToken(token: String)
    suspend fun saveGeminiApiKey(key: String)

    /** True once both secrets have been saved (used to gate starting the bridge later). */
    val hasCredentials: Flow<Boolean>
}
