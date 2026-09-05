package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Email
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
        val user = supabase.auth.currentUserOrNull() ?: return false
        return !user.isAnonymous
    }

    fun isAnonymousSession(): Boolean =
        supabase.auth.currentUserOrNull()?.isAnonymous == true

    fun currentEmail(): String? = supabase.auth.currentUserOrNull()?.email

    suspend fun startAnonymousAccount(): Result<Unit> = runCatching {
        if (supabase.auth.currentUserOrNull() == null) {
            supabase.auth.signInAnonymously()
        }
    }

    suspend fun linkEmailToCurrentAnonymous(email: String): Result<Unit> = runCatching {
        require(isAnonymousSession()) { "ANONYMOUS_ACCOUNT_REQUIRED" }
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        supabase.auth.updateUser {
            this.email = normalized
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "EMAIL_REQUIRED" }
        require(password.isNotBlank()) { "PASSWORD_REQUIRED" }
        supabase.auth.signInWith(Email) {
            this.email = normalized
            this.password = password
        }
        check(isAuthenticated()) { "ACCOUNT_AUTH_FAILED" }
    }

    suspend fun setPassword(password: String): Result<Unit> = runCatching {
        require(isAuthenticated()) { "ACCOUNT_NOT_VERIFIED" }
        require(password.length >= 8) { "PASSWORD_TOO_SHORT" }
        supabase.auth.updateUser {
            this.password = password
        }
    }

    suspend fun refreshAccountState(): Result<Boolean> = runCatching {
        val user = supabase.auth.retrieveUserForCurrentSession(updateSession = true)
        !user.isAnonymous
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }
}
