package com.sigmabridge.app.data.auth

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.sigmabridge.app.BuildConfig
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small Android-only adapter for Google's native account picker.
 * It returns the Google ID token; Supabase remains responsible for the app session.
 */
@Singleton
class GoogleSignInManager @Inject constructor() {
    suspend fun signIn(activity: Activity): Result<String> = runCatching {
        val serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        require(serverClientId.isNotBlank()) { "GOOGLE_WEB_CLIENT_ID_MISSING" }

        val googleOption = GetSignInWithGoogleOption.Builder(
            serverClientId = serverClientId
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val credentialManager = CredentialManager.create(activity)
        val result = credentialManager.getCredential(
            context = activity,
            request = request
        )

        val credential = result.credential
        require(credential is CustomCredential) { "GOOGLE_CREDENTIAL_UNEXPECTED" }
        require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "GOOGLE_CREDENTIAL_TYPE_UNEXPECTED"
        }

        try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: GoogleIdTokenParsingException) {
            throw IllegalStateException("GOOGLE_ID_TOKEN_INVALID", error)
        }
    }

    @Suppress("unused")
    private fun secureNonce(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }
}
