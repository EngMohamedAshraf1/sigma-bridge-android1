package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.OtpType
import io.github.jan.supabase.gotrue.providers.builtin.Email
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatAccountRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val auth: Auth
        get() = supabase.pluginManager.getPlugin(Auth)

    suspend fun awaitAuthInitialization() {
        auth.awaitInitialization()
    }

    fun isAuthenticated(): Boolean {
        val user = auth.currentUserOrNull() ?: return false
        return !isAnonymousUser(user)
    }

    fun isAnonymousSession(): Boolean =
        auth.currentUserOrNull()?.let(::isAnonymousUser) == true

    fun currentEmail(): String? = auth.currentUserOrNull()?.email

    suspend fun startAnonymousAccount(): Result<Unit> = runCatching {
        auth.awaitInitialization()
        if (auth.currentUserOrNull() == null) {
            auth.signInAnonymously()
        }
    }

    suspend fun linkEmailToCurrentAnonymous(email: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        require(isAnonymousSession()) { "ANONYMOUS_ACCOUNT_REQUIRED" }
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        auth.updateUser {
            this.email = normalized
        }
    }

    suspend fun verifyEmailChangeOtp(email: String, token: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(token.isNotBlank()) { "OTP_REQUIRED" }

        auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL_CHANGE,
            email = normalized,
            token = token.trim()
        )

        val user = auth.retrieveUserForCurrentSession(updateSession = true)
        check(!isAnonymousUser(user)) { "EMAIL_NOT_VERIFIED" }
    }

    suspend fun resendEmailVerification(email: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        auth.resendEmail(OtpType.Email.EMAIL_CHANGE, normalized)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(password.isNotBlank()) { "PASSWORD_REQUIRED" }
        auth.signInWith(Email) {
            this.email = normalized
            this.password = password
        }
        check(isAuthenticated()) { "ACCOUNT_AUTH_FAILED" }
    }

    suspend fun setPassword(password: String): Result<Unit> = runCatching {
        auth.awaitInitialization()
        require(isAuthenticated()) { "ACCOUNT_NOT_VERIFIED" }
        require(password.length >= 8) { "PASSWORD_TOO_SHORT" }
        auth.updateUser {
            this.password = password
        }
    }

    suspend fun refreshAccountState(): Result<Boolean> = runCatching {
        auth.awaitInitialization()
        val user = auth.retrieveUserForCurrentSession(updateSession = true)
        !isAnonymousUser(user)
    }

    suspend fun signOut() {
        auth.signOut()
    }

    private fun isAnonymousUser(user: io.github.jan.supabase.gotrue.user.UserInfo): Boolean {
        val identities = user.identities.orEmpty()
        return identities.isEmpty() || identities.all { it.provider == "anonymous" }
    }
}
