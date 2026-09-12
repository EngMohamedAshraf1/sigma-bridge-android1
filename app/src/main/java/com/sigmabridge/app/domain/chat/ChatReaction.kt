package com.sigmabridge.app.domain.chat

data class ChatReaction(
    val messageId: String,
    val userId: String,
    val emoji: String,
    val createdAt: Long
)
