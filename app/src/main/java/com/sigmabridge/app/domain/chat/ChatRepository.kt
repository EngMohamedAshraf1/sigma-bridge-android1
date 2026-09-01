package com.sigmabridge.app.domain.chat

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun send(topic: String, message: ChatMessage): Result<Unit>
    suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit>
    suspend fun sendReadReceipt(topic: String, receipt: ChatReceipt): Result<Unit>
    fun observeEvents(topic: String, ownSenderId: String): Flow<ChatEvent>
}
