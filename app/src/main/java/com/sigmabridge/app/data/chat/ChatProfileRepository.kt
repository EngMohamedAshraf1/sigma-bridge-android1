package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatProfileRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager,
    private val identity: ChatIdentity
) {
    private val auth: Auth
        get() = supabase.pluginManager.getPlugin(Auth)

    suspend fun getMyProfile(): Result<ChatProfile?> = runCatching {
        ensureIdentityRegistered()
        supabase.postgrest.rpc("sigma_get_my_profile")
            .decodeList<ChatProfile>()
            .firstOrNull()
    }

    suspend fun getProfileByPublicId(publicId: String): Result<ChatProfile?> = runCatching {
        ensureIdentityRegistered()
        supabase.postgrest.rpc(
            "sigma_get_profile_by_public_id",
            GetChatProfileByPublicIdRpcParams(publicId)
        ).decodeList<ChatProfile>().firstOrNull()
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

    suspend fun uploadAvatar(bytes: ByteArray, extension: String): Result<ChatProfile> = runCatching {
        ensureIdentityRegistered()
        require(bytes.isNotEmpty()) { "AVATAR_EMPTY" }
        require(bytes.size <= 5 * 1024 * 1024) { "AVATAR_TOO_LARGE" }

        val safeExtension = extension.lowercase().let {
            if (it in setOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }
        val userId = auth.currentUserOrNull()?.id ?: error("AUTH_REQUIRED")
        val path = "$userId/avatar.$safeExtension"
        supabase.storage["chat_avatars"].upload(path, bytes, upsert = true)

        supabase.postgrest.rpc(
            "sigma_update_avatar",
            UpdateChatAvatarRpcParams(path)
        ).decodeList<ChatProfile>().firstOrNull()
            ?: error("Avatar update returned no profile.")
    }

    suspend fun searchUsers(query: String): Result<List<ChatProfile>> = runCatching {
        ensureIdentityRegistered()
        supabase.postgrest.rpc(
            "sigma_search_users",
            SearchChatUsersRpcParams(query.trim().lowercase())
        ).decodeList<ChatProfile>()
    }

    /** Register this installation/device independently of searching for someone. */
    suspend fun ensureIdentityRegistered() {
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
