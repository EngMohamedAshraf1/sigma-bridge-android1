package com.sigmabridge.app.presentation.chat

/**
 * Convenience accessors for the conversation list UI.
 * The actual values are stored inside ChatConversation.
 */
val ChatConversationRow.lastMessage: String
    get() = conversation.lastMessage

val ChatConversationRow.lastMessageAt: Long
    get() = conversation.lastMessageAt
