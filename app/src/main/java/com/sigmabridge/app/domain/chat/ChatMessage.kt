package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Long
)
