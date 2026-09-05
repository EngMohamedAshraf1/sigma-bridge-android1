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
        ensureIdentityRegistered()
        supabase.postgrest.rpc("sigma_get_my_profile")
            .decodeList<ChatProfile>()
            .firstOrNull()
    }

    suspend fun updateProfile(
        firstName: String,
        lastName: String,
        username: String
    ): Result<ChatProfile> = runCatching {
        ensureIdentityRegistered()
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
        ensureIdentityRegistered()
        supabase.postgrest.rpc(
            "sigma_search_users",
            SearchChatUsersRpcParams(query.trim().lowercase())
        ).decodeList<ChatProfile>()
    }

    private suspend fun ensureIdentityRegistered() {
        sessionManager.ensureAnonymousSession().getOrThrow()

        for (attempt in 0 until 2) {
            try {
                supabase.postgrest.rpc(
                    "sigma_register_device",
                    RegisterDeviceRpcParams(
                        publicId = identity.myId,
                        devicePublicId = identity.devicePublicId,
                        identityPublicKey = identity.legacyIdentityKey
                    )
                ).decodeList<RegisterDeviceRpcResult>().firstOrNull()
                    ?: error("Supabase device registration returned no device.")
                return
            } catch (error: Throwable) {
                val isPublicIdConflict = error.message
                    ?.contains("PUBLIC_ID_ALREADY_IN_USE", ignoreCase = true) == true
                if (attempt == 0 && isPublicIdConflict) {
                    identity.regenerateMyId()
                    continue
                }
                throw error
            }
        }

        error("Supabase identity registration failed after recovery.")
    }
}
