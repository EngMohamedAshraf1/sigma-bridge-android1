package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.providers.builtin.OTP
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple passwordless authentication for Private Chat.
 *
 * The chat no longer creates anonymous accounts or performs email linking.
 * Supabase Auth owns the account identity; email is only used to start a
 * passwordless sign-in flow. The same call creates a new user when needed.
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

    fun isAuthenticated(): Boolean = auth.currentUserOrNull() != null

    fun currentUserId(): String? = auth.currentUserOrNull()?.id

    fun currentEmail(): String? = auth.currentUserOrNull()?.email

    suspend fun requestEmailLogin(email: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(normalized.contains("@") && normalized.length >= 5) { "INVALID_EMAIL" }

        // Supabase sends a passwordless email. With the default template this is
        // a magic link; once a custom OTP template is enabled, the same API can
        // deliver a 6-digit code without changing the rest of the chat flow.
        auth.signInWith(OTP) {
            email = normalized
        }
    }

    suspend fun verifyEmailOtp(email: String, token: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(token.isNotBlank()) { "OTP_REQUIRED" }

        auth.verifyEmailOtp(
            type = io.github.jan.supabase.gotrue.OtpType.Email.EMAIL,
            email = normalized,
            token = token.trim()
        )
    }

    suspend fun signOut() {
        auth.awaitInitialization()
        auth.signOut()
    }
}
