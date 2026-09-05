package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatProfileRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager,
    private val identity: ChatIdentity
) {
    suspend fun getMyProfile(): Result<ChatProfile?> = runCatching {
        sessionManager.ensureAnonymousSession().getOrThrow()
        supabase.postgrest.rpc("sigma_get_my_profile")
            .decodeList<ChatProfile>()
            .firstOrNull()
    }

    suspend fun updateProfile(
        firstName: String,
        lastName: String,
        username: String
    ): Result<ChatProfile> = runCatching {
        sessionManager.ensureAnonymousSession().getOrThrow()
        supabase.postgrest.rpc(
            "sigma_update_profile",
            UpdateChatProfileRpcParams(
                publicId = identity.myId,
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                username = username.trim().lowercase()
            )
        ).decodeList<ChatProfile>().firstOrNull()
            ?: error("Profile update returned no profile.")
    }

    suspend fun searchUsers(query: String): Result<List<ChatProfile>> = runCatching {
        sessionManager.ensureAnonymousSession().getOrThrow()
        supabase.postgrest.rpc(
            "sigma_search_users",
            SearchChatUsersRpcParams(query.trim().lowercase())
        ).decodeList<ChatProfile>()
    }
}
