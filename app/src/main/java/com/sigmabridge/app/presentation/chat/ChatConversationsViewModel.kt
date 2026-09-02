package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatHistoryStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.ChatUnreadStore
import com.sigmabridge.app.domain.chat.ChatConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ChatConversationsViewModel @Inject constructor(
    private val conversationStore: ChatConversationStore,
    private val historyStore: ChatHistoryStore,
    private val unreadStore: ChatUnreadStore,
    private val identity: ChatIdentity
) : ViewModel() {
    private val _conversations = MutableStateFlow<List<ChatConversationRow>>(emptyList())
    val conversations: StateFlow<List<ChatConversationRow>> = _conversations.asStateFlow()

    init {
        migrateCurrentPartner()
        refresh()
    }

    fun refresh() {
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

    fun openConversation(conversation: ChatConversation) {
        identity.partnerId = conversation.partnerId
    }

    fun addConversation(partnerId: String, displayName: String): Boolean {
        val normalizedId = partnerId.trim()
        if (normalizedId.isBlank() || normalizedId == identity.myId) return false
        val normalizedName = displayName.trim().ifBlank { normalizedId }
        conversationStore.upsert(ChatConversation(partnerId = normalizedId, displayName = normalizedName))
        identity.partnerId = normalizedId
        refresh()
        return true
    }

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
}

data class ChatConversationRow(
    val conversation: ChatConversation,
    val unreadCount: Int
)
