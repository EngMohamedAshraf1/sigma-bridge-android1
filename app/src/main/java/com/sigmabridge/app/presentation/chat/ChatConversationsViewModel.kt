package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import com.sigmabridge.app.data.chat.ChatConversationStore
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.domain.chat.ChatConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ChatConversationsViewModel @Inject constructor(
    private val conversationStore: ChatConversationStore,
    private val identity: ChatIdentity
) : ViewModel() {
    private val _conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    val conversations: StateFlow<List<ChatConversation>> = _conversations.asStateFlow()

    init {
        migrateCurrentPartner()
        refresh()
    }

    fun refresh() {
        _conversations.value = conversationStore.load()
    }

    fun openConversation(conversation: ChatConversation) {
        identity.partnerId = conversation.partnerId
    }

    fun addConversation(partnerId: String, displayName: String) {
        val normalizedId = partnerId.trim()
        if (normalizedId.isBlank() || normalizedId == identity.myId) return
        val normalizedName = displayName.trim().ifBlank { normalizedId }
        conversationStore.upsert(ChatConversation(partnerId = normalizedId, displayName = normalizedName))
        identity.partnerId = normalizedId
        refresh()
    }

    fun rename(conversation: ChatConversation, newName: String) {
        conversationStore.updateName(conversation.partnerId, newName)
        refresh()
    }

    fun delete(conversation: ChatConversation) {
        conversationStore.remove(conversation.partnerId)
        if (identity.partnerId == conversation.partnerId) {
            identity.partnerId = ""
        }
        refresh()
    }

    private fun migrateCurrentPartner() {
        val currentPartner = identity.partnerId.trim()
        if (currentPartner.isBlank() || currentPartner == identity.myId) return
        val exists = conversationStore.load().any { it.partnerId == currentPartner }
        if (!exists) {
            conversationStore.upsert(
                ChatConversation(
                    partnerId = currentPartner,
                    displayName = currentPartner
                )
            )
        }
    }
}
