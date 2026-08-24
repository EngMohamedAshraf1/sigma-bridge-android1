package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatProfile(
    val username: String,
    val userId: String
)
