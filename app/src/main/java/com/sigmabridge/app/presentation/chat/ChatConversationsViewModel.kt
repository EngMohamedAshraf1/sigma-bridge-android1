package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatCrypto
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatLanguagePreferences
import com.sigmabridge.app.data.chat.ChatProfile
import com.sigmabridge.app.data.chat.ChatProfileRepository
import com.sigmabridge.app.data.chat.ChatUnreadStore
import com.sigmabridge.app.data.chat.SupabaseSessionManager
import com.sigmabridge.app.domain.chat.ChatConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatConversationsViewModel @Inject constructor(
    private val conversationStore: ChatConversationStore,
    private val historyStore: ChatHistoryStore,
    private val unreadStore: ChatUnreadStore,
    private val identity: ChatIdentity,
    private val profileRepository: ChatProfileRepository,
    private val crypto: ChatCrypto,
    private val sessionManager: SupabaseSessionManager,
    private val languagePreferences: ChatLanguagePreferences
) : ViewModel() {
    private val _conversations = MutableStateFlow<List<ChatConversationRow>>(emptyList())
    val conversations: StateFlow<List<ChatConversationRow>> = _conversations.asStateFlow()

    private val _profile = MutableStateFlow<ChatProfile?>(null)
    val profile: StateFlow<ChatProfile?> = _profile.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ChatProfile>>(emptyList())
    val searchResults: StateFlow<List<ChatProfile>> = _searchResults.asStateFlow()

    private val _profileBusy = MutableStateFlow(false)
    val profileBusy: StateFlow<Boolean> = _profileBusy.asStateFlow()

    private val _searchBusy = MutableStateFlow(false)
    val searchBusy: StateFlow<Boolean> = _searchBusy.asStateFlow()

    private val _profileError = MutableStateFlow<String?>(null)
    val profileError: StateFlow<String?> = _profileError.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    init {
        clearLegacySelectionIfNeeded()
        refresh()
        loadProfile()
        syncCloudConversations()
    }

    fun refresh() {
        _conversations.value = renderLocalConversations()
    }

    private fun renderLocalConversations(): List<ChatConversationRow> =
        conversationStore.load()
            .filter { it.partnerId.isNotBlank() && it.conversationId.isNotBlank() }
            .map { conversation ->
                val history = historyStore.load(conversation.conversationId)
                val lastMessage = history.lastOrNull()
                ChatConversationRow(
                    conversation = conversation.copy(
                        lastMessage = lastMessage?.text ?: conversation.lastMessage,
                        lastMessageAt = lastMessage?.createdAt ?: conversation.lastMessageAt
                    ),
                    unreadCount = unreadStore.load(conversation.conversationId).size
                )
            }
            .sortedByDescending { it.conversation.lastMessageAt }

    private fun syncCloudConversations() {
        viewModelScope.launch {
            runCatching {
                profileRepository.ensureAccountDeviceRegistered()
                val rows = profileRepository.getMyConversations().getOrThrow()
                val ownUserId = sessionManager.currentUserId() ?: error("AUTH_REQUIRED")

                rows.map { row ->
                    val displayName = listOf(
                        row.partnerFirstName.trim(),
                        row.partnerLastName.trim()
                    ).filter(String::isNotBlank).joinToString(" ")
                        .ifBlank { row.partnerUsername.ifBlank { "Private Chat" }.let { "@$it" } }

                    val preview = if (row.lastCiphertext?.startsWith("sb3:") == true) {
                        runCatching {
                            crypto.decryptForAccountPair(
                                row.lastCiphertext,
                                ownUserId,
                                row.partnerUserId
                            )
                        }.getOrDefault("")
                    } else {
                        ""
                    }

                    ChatConversation(
                        conversationId = row.conversationId,
                        partnerId = row.partnerUserId,
                        displayName = displayName,
                        lastMessage = preview,
                        lastMessageAt = row.lastCreatedAt?.let(::parseTimestamp) ?: 0L,
                        avatarPath = row.partnerAvatarPath
                    )
                }
            }.onSuccess { restored ->
                conversationStore.replaceAll(restored)
                _conversations.value = renderLocalConversations()
            }.onFailure { error ->
                _profileError.value = friendlyError(error)
            }
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            _profileError.value = null
            _profile.value = profileRepository.getMyProfile().getOrElse {
                _profileError.value = friendlyError(it)
                null
            }
        }
    }

    fun saveProfile(firstName: String, lastName: String, username: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            _profileBusy.value = true
            _profileError.value = null
            val result = profileRepository.updateProfile(firstName, lastName, username)
            result.onSuccess {
                _profile.value = it
                onSaved()
            }.onFailure {
                _profileError.value = friendlyError(it)
            }
            _profileBusy.value = false
        }
    }

    fun uploadAvatar(bytes: ByteArray, extension: String) {
        viewModelScope.launch {
            _profileBusy.value = true
            _profileError.value = null
            profileRepository.uploadAvatar(bytes, extension)
                .onSuccess {
                    _profile.value = it
                    refresh()
                }
                .onFailure { _profileError.value = friendlyError(it) }
            _profileBusy.value = false
        }
    }

    fun searchUsers(query: String) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            _searchResults.value = emptyList()
            _searchError.value = null
            return
        }

        viewModelScope.launch {
            _searchBusy.value = true
            _searchError.value = null
            profileRepository.searchUsers(normalized)
                .onSuccess { _searchResults.value = it }
                .onFailure {
                    _searchResults.value = emptyList()
                    _searchError.value = friendlyError(it)
                }
            _searchBusy.value = false
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    fun openConversation(conversation: ChatConversation) {
        identity.partnerId = conversation.partnerId
        identity.selectedConversationId = conversation.conversationId
    }

    /**
     * Starts a new cloud conversation before opening the chat screen.
     * This guarantees ChatViewModel always receives a durable conversation_id.
     */
    fun addProfileToConversation(profile: ChatProfile, onReady: () -> Unit = {}) {
        val userId = profile.userId.trim()
        if (userId.isBlank()) {
            _searchError.value = "هذا الحساب لا يملك هوية حساب سحابية صالحة."
            return
        }

        viewModelScope.launch {
            _searchBusy.value = true
            _searchError.value = null
            profileRepository.ensureConversationWithUser(userId)
                .onSuccess { conversationId ->
                    conversationStore.upsert(
                        ChatConversation(
                            conversationId = conversationId,
                            partnerId = userId,
                            displayName = profile.displayName,
                            avatarPath = profile.avatarPath
                        )
                    )
                    identity.partnerId = userId
                    identity.selectedConversationId = conversationId
                    refresh()
                    onReady()
                }
                .onFailure { _searchError.value = friendlyError(it) }
            _searchBusy.value = false
        }
    }

    fun rename(conversation: ChatConversation, newName: String) {
        conversationStore.updateName(conversation.partnerId, newName)
        refresh()
    }

    fun delete(conversation: ChatConversation) {
        // Local deletion remains a cache operation; it does not delete the server account,
        // conversation, or messages.
        conversationStore.remove(conversation)
        if (identity.selectedConversationId == conversation.conversationId) {
            identity.partnerId = ""
            identity.selectedConversationId = ""
        }
        refresh()
    }

    private fun clearLegacySelectionIfNeeded() {
        if (!isUuid(identity.partnerId) || identity.selectedConversationId.isBlank()) {
            identity.partnerId = ""
            identity.selectedConversationId = ""
        }
    }

    private fun isUuid(value: String): Boolean =
        runCatching { java.util.UUID.fromString(value.trim()) }.isSuccess

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { 0L }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("USERNAME_ALREADY_IN_USE", ignoreCase = true) ->
                "اسم المستخدم مستخدم بالفعل."
            raw.contains("INVALID_USERNAME", ignoreCase = true) ->
                "اسم المستخدم: أحرف إنجليزية صغيرة وأرقام و _ فقط، من 3 إلى 24 حرفًا."
            raw.contains("NAME_TOO_LONG", ignoreCase = true) ->
                "الاسم طويل جدًا."
            raw.contains("AVATAR_EMPTY", ignoreCase = true) ->
                "اختر صورة أولًا."
            raw.contains("AVATAR_TOO_LARGE", ignoreCase = true) ->
                "حجم الصورة يجب أن يكون أقل من 5 MB."
            raw.contains("PARTNER_NOT_FOUND", ignoreCase = true) ->
                "الحساب المطلوب غير مسجل على Sigma Bridge."
            raw.contains("AUTH_REQUIRED", ignoreCase = true) ->
                "تعذر فتح جلسة حساب Sigma Bridge الآن."
            raw.contains("SUPABASE", ignoreCase = true) || raw.contains("HTTP 400", ignoreCase = true) ->
                "تعذر الاتصال بخادم المحادثات. حاول مرة أخرى."
            else -> raw.ifBlank { "حدث خطأ غير متوقع." }
        }
    }
}

data class ChatConversationRow(
    val conversation: ChatConversation,
    val unreadCount: Int
)
