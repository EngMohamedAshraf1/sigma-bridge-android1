package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable

/** Persistent metadata for one private-chat conversation. */
@Serializable
data class ChatConversation(
    val partnerId: String,
    val displayName: String,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0L
)
