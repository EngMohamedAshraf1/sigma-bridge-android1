package com.sigmabridge.app.data.chat

import com.sigmabridge.app.data.auth.GoogleIdTokenResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple Private Chat authentication backed by Supabase Auth.
 *
 * Google is the only sign-in method for now. The provider can be extended later
 * without changing the conversation/message model because chat ownership uses
 * Supabase auth.uid().
 */
@Singleton
class ChatAccountRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val auth: Auth
        get() = supabase.pluginManager.getPlugin(Auth)

    suspend fun awaitAuthInitialization() {
        auth.awaitInitialization()
    }

    // Legacy anonymous sessions from older builds are not considered signed in.
    fun isAuthenticated(): Boolean = auth.currentUserOrNull()?.email != null

    fun currentUserId(): String? = auth.currentUserOrNull()?.id

    fun currentEmail(): String? = auth.currentUserOrNull()?.email

    suspend fun signInWithGoogleIdToken(credentials: GoogleIdTokenResult): Result<Unit> = runCatching {
        auth.awaitInitialization()
        require(credentials.idToken.isNotBlank()) { "GOOGLE_ID_TOKEN_REQUIRED" }
        require(credentials.nonce.isNotBlank()) { "GOOGLE_NONCE_REQUIRED" }
        auth.signInWith(IDToken) {
            idToken = credentials.idToken
            provider = Google
            nonce = credentials.nonce
        }
    }

    suspend fun signOut() {
        auth.awaitInitialization()
        auth.signOut()
    }
}
