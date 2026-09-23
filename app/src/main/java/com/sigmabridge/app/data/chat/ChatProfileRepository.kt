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
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc("sigma_get_my_profile_v2")
            .decodeList<ChatProfile>()
            .firstOrNull()
    }

    suspend fun getProfileByUserId(userId: String): Result<ChatProfile?> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc(
            "sigma_get_profile_by_user_id_v2",
            GetChatProfileByUserIdRpcParams(userId)
        ).decodeList<ChatProfile>().firstOrNull()
    }

    /** Legacy public-ID lookup is intentionally no longer part of the v2 flow. */
    suspend fun getProfileByPublicId(publicId: String): Result<ChatProfile?> =
        Result.failure(IllegalStateException("LEGACY_PUBLIC_ID_LOOKUP_DISABLED"))

    suspend fun getLastSeenByUserId(userId: String): Result<Long?> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc(
            "sigma_get_last_seen_v2",
            GetLastSeenByUserIdRpcParams(userId)
        ).decodeList<ChatLastSeenRpcResponse>().firstOrNull()?.lastSeenAt
    }

    suspend fun getLastSeenByPublicId(publicId: String): Result<Long?> =
        Result.failure(IllegalStateException("LEGACY_PUBLIC_ID_LOOKUP_DISABLED"))

    suspend fun touchMyLastSeen(): Result<Long?> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc("sigma_touch_last_seen")
            .decodeList<ChatLastSeenRpcResponse>()
            .firstOrNull()
            ?.lastSeenAt
    }

    suspend fun updateProfile(
        firstName: String,
        lastName: String,
        username: String
    ): Result<ChatProfile> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc(
            "sigma_update_profile_v2",
            UpdateChatProfileV2RpcParams(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                username = username.trim().lowercase()
            )
        ).decodeList<ChatProfile>().firstOrNull()
            ?: error("Profile update returned no profile.")
    }

    suspend fun uploadAvatar(bytes: ByteArray, extension: String): Result<ChatProfile> = runCatching {
        ensureAccountDeviceRegistered()
        require(bytes.isNotEmpty()) { "AVATAR_EMPTY" }
        require(bytes.size <= 5 * 1024 * 1024) { "AVATAR_TOO_LARGE" }

        val safeExtension = extension.lowercase().let {
            if (it in setOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }
        val userId = auth.currentUserOrNull()?.id ?: error("AUTH_REQUIRED")
        val path = "$userId/avatar_${System.currentTimeMillis()}.$safeExtension"

        supabase.storage["chat_avatars"].upload(path, bytes, upsert = false)
        supabase.postgrest.rpc(
            "sigma_update_avatar",
            UpdateChatAvatarRpcParams(path)
        ).decodeList<ChatProfile>()

        getMyProfile().getOrThrow() ?: error("Avatar update returned no profile.")
    }

    suspend fun searchUsers(query: String): Result<List<ChatProfile>> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc(
            "sigma_search_users_v2",
            SearchChatUsersRpcParams(query.trim().lowercase())
        ).decodeList<ChatProfile>()
    }

    suspend fun getMyConversations(): Result<List<SupabaseConversationV2Row>> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc("sigma_get_my_conversations_v2")
            .decodeList<SupabaseConversationV2Row>()
    }

    suspend fun ensureConversationWithUser(partnerUserId: String): Result<String> = runCatching {
        ensureAccountDeviceRegistered()
        supabase.postgrest.rpc(
            "sigma_ensure_conversation_v2",
            EnsureConversationV2RpcParams(partnerUserId)
        ).decodeAs<String>()
    }

    suspend fun ensureAccountDeviceRegistered(): RegisterAccountDeviceRpcResult {
        sessionManager.ensureAuthenticatedSession().getOrThrow()

        val result = supabase.postgrest.rpc(
            "sigma_register_account_device_v2",
            RegisterAccountDeviceRpcParams(
                devicePublicId = identity.devicePublicId,
                identityPublicKey = identity.deviceIdentityKey
            )
        ).decodeList<RegisterAccountDeviceRpcResult>().firstOrNull()
            ?: error("Supabase account device registration returned no device.")

        identity.syncDeviceRole(result.deviceRole)
        return result
    }

    suspend fun ensureIdentityRegistered() {
        ensureAccountDeviceRegistered()
    }
}
