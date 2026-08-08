package com.sigmabridge.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sigmabridge.app.domain.repository.OwnerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE_NAME = "sigma_bridge_owner_prefs"
private const val KEY_OWNER_USER_ID = "owner_user_id"

/** Same construction as SecureSettingsRepository/SecureLanguagePreferencesRepository — own file, since this is yet another independent concern (authorization, not credentials or preferences). */
@Singleton
class SecureOwnerRepository @Inject constructor(
    @ApplicationContext context: Context
) : OwnerRepository {

    private val mutex = Mutex()

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

    override suspend fun getOwnerUserId(): Long? {
        val stored = prefs.getLong(KEY_OWNER_USER_ID, NO_OWNER)
        return if (stored == NO_OWNER) null else stored
    }

    override suspend fun claimOwnershipIfUnset(userId: Long): Boolean = mutex.withLock {
        val current = prefs.getLong(KEY_OWNER_USER_ID, NO_OWNER)
        if (current != NO_OWNER) return@withLock false
        prefs.edit().putLong(KEY_OWNER_USER_ID, userId).apply()
        true
    }

    private companion object {
        const val NO_OWNER = -1L
    }
}
