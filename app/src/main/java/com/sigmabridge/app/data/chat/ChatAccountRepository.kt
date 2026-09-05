package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.OtpType
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

    fun isAuthenticated(): Boolean = auth.currentUserOrNull()?.email != null

    fun currentUserId(): String? = auth.currentUserOrNull()?.id

    fun currentEmail(): String? = auth.currentUserOrNull()?.email

    suspend fun requestEmailLogin(email: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(normalized.contains("@") && normalized.length >= 5) { "INVALID_EMAIL" }

        // Drop a legacy anonymous session from older app builds so the new
        // passwordless account flow always starts from a clean Auth session.
        if (auth.currentUserOrNull()?.email == null) {
            auth.signOut()
        }

        // With the default Supabase email template this sends a magic link.
        // When the template is later changed to {{ .Token }}, this same API
        // becomes a six-digit email OTP flow without changing the chat model.
        auth.signInWith(OTP) {
            this.email = normalized
        }
    }

    suspend fun verifyEmailOtp(email: String, token: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(token.isNotBlank()) { "OTP_REQUIRED" }

        auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = normalized,
            token = token.trim()
        )
    }

    suspend fun signOut() {
        auth.awaitInitialization()
        auth.signOut()
    }
}
