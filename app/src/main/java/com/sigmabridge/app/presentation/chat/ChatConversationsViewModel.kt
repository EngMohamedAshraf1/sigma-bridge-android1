package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatProfile
import com.sigmabridge.app.data.chat.ChatProfileRepository
import com.sigmabridge.app.data.chat.ChatUnreadStore
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
    private val profileRepository: ChatProfileRepository
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

    /** Kept internally for legacy conversation migration; no longer the primary user-facing identity. */
    val myId: String
        get() = identity.myId

    init {
        migrateCurrentPartner()
        refresh()
        loadProfile()
    }

    fun refresh() {
        val rows = conversationStore.load().map { conversation ->
            val historyKey = historyKeyFor(conversation.partnerId)
            val lastMessage = historyStore.load(historyKey).lastOrNull()
            ChatConversationRow(
                conversation = conversation.copy(
                    lastMessage = lastMessage?.text ?: conversation.lastMessage,
                    lastMessageAt = lastMessage?.createdAt ?: conversation.lastMessageAt
                ),
                unreadCount = unreadStore.load(historyKey).size
            )
        }.sortedByDescending { it.conversation.lastMessageAt }

        _conversations.value = rows

        if (rows.isNotEmpty()) {
            viewModelScope.launch {
                rows.forEach { row ->
                    profileRepository.getProfileByPublicId(row.conversation.partnerId)
                        .getOrNull()
                        ?.let { profile ->
                            val updated = row.conversation.copy(
                                avatarPath = profile.avatarPath
                            )
                            conversationStore.upsert(updated)
                        }
                }
                _conversations.value = conversationStore.load().map { conversation ->
                    val historyKey = historyKeyFor(conversation.partnerId)
                    val lastMessage = historyStore.load(historyKey).lastOrNull()
                    ChatConversationRow(
                        conversation = conversation.copy(
                            lastMessage = lastMessage?.text ?: conversation.lastMessage,
                            lastMessageAt = lastMessage?.createdAt ?: conversation.lastMessageAt
                        ),
                        unreadCount = unreadStore.load(historyKey).size
                    )
                }.sortedByDescending { it.conversation.lastMessageAt }
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
                .onSuccess { _profile.value = it; refresh() }
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
    }

    fun addConversation(partnerId: String, displayName: String, avatarPath: String? = null): Boolean {
        val normalizedId = partnerId.trim()
        if (normalizedId.isBlank() || normalizedId == identity.myId) return false
        val normalizedName = displayName.trim().ifBlank { normalizedId }
        conversationStore.upsert(
            ChatConversation(
                partnerId = normalizedId,
                displayName = normalizedName,
                avatarPath = avatarPath
            )
        )
        identity.partnerId = normalizedId
        refresh()
        return true
    }

    fun addProfileToConversation(profile: ChatProfile): Boolean =
        addConversation(profile.publicId, profile.displayName, profile.avatarPath)

    fun rename(conversation: ChatConversation, newName: String) {
        conversationStore.updateName(conversation.partnerId, newName)
        refresh()
    }

    fun delete(conversation: ChatConversation) {
        conversationStore.remove(conversation.partnerId)
        if (identity.partnerId == conversation.partnerId) identity.partnerId = ""
        refresh()
    }

    private fun migrateCurrentPartner() {
        val currentPartner = identity.partnerId.trim()
        if (currentPartner.isBlank() || currentPartner == identity.myId) return
        if (conversationStore.load().none { it.partnerId == currentPartner }) {
            conversationStore.upsert(
                ChatConversation(
                    partnerId = currentPartner,
                    displayName = currentPartner
                )
            )
        }
    }

    private fun historyKeyFor(partnerId: String): String =
        identity.conversationKeyFor(partnerId).joinToString("") { "%02x".format(it) }

    private fun friendlyError(error: Throwable): String =
        when {
            error.message?.contains("USERNAME_ALREADY_IN_USE", ignoreCase = true) == true -> "اسم المستخدم مستخدم بالفعل."
            error.message?.contains("INVALID_USERNAME", ignoreCase = true) == true -> "اسم المستخدم: أحرف إنجليزية صغيرة وأرقام و _ فقط، من 3 إلى 24 حرفًا."
            error.message?.contains("NAME_TOO_LONG", ignoreCase = true) == true -> "الاسم طويل جدًا."
            error.message?.contains("AVATAR_EMPTY", ignoreCase = true) == true -> "اختر صورة أولًا."
            error.message?.contains("AVATAR_TOO_LARGE", ignoreCase = true) == true -> "حجم الصورة يجب أن يكون أقل من 5 MB."
            error.message?.contains("AUTH_REQUIRED", ignoreCase = true) == true -> "تعذر فتح جلسة الحساب الآن."
            else -> error.message ?: "حدث خطأ غير متوقع."
        }
}

data class ChatConversationRow(
    val conversation: ChatConversation,
    val unreadCount: Int
)
