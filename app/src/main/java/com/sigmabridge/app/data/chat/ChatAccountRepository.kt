package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.gotrue
import io.github.jan.supabase.gotrue.providers.builtin.Email
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permanent account layer for Private Chat.
 *
 * An existing anonymous Supabase user can be upgraded by linking an email identity.
 * This preserves the same auth user UUID and therefore preserves existing chat data.
 */
@Singleton
class ChatAccountRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    fun isAuthenticated(): Boolean {
        val user = supabase.gotrue.currentUserOrNull() ?: return false
        return !user.isAnonymous
    }

    fun isAnonymousSession(): Boolean =
        supabase.gotrue.currentUserOrNull()?.isAnonymous == true

    fun currentEmail(): String? = supabase.gotrue.currentUserOrNull()?.email

    suspend fun startAnonymousAccount(): Result<Unit> = runCatching {
        if (supabase.gotrue.currentUserOrNull() == null) {
            supabase.gotrue.signInAnonymously()
        }
    }

    suspend fun linkEmailToCurrentAnonymous(email: String): Result<Unit> = runCatching {
        require(isAnonymousSession()) { "ANONYMOUS_ACCOUNT_REQUIRED" }
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        supabase.gotrue.updateUser {
            this.email = normalized
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(password.isNotBlank()) { "PASSWORD_REQUIRED" }
        supabase.gotrue.loginWith(Email) {
            this.email = normalized
            this.password = password
        }
        check(isAuthenticated()) { "ACCOUNT_AUTH_FAILED" }
    }

    suspend fun setPassword(password: String): Result<Unit> = runCatching {
        require(isAuthenticated()) { "ACCOUNT_NOT_VERIFIED" }
        require(password.length >= 8) { "PASSWORD_TOO_SHORT" }
        supabase.gotrue.updateUser {
            this.password = password
        }
    }

    suspend fun refreshAccountState(): Result<Boolean> = runCatching {
        val user = supabase.gotrue.retrieveUserForCurrentSession(updateSession = true)
        !user.isAnonymous
    }

    suspend fun signOut() {
        supabase.gotrue.logout()
    }
}
