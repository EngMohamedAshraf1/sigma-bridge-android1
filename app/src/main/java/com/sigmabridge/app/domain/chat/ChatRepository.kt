package com.sigmabridge.app.domain.chat

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun send(topic: String, message: ChatMessage): Result<Unit>
    fun observe(topic: String, ownSenderId: String): Flow<ChatMessage>
    suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit>
    fun observeDeliveredReceipts(topic: String, ownSenderId: String): Flow<ChatReceipt>
}
