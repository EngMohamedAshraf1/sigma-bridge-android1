package com.sigmabridge.app.domain.chat

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun send(topic: String, message: ChatMessage): Result<Unit>
    suspend fun sendDeliveredReceipt(topic: String, receipt: ChatReceipt): Result<Unit>
    suspend fun sendReadReceipt(topic: String, receipt: ChatReceipt): Result<Unit>

    /** Observe one or more conversation topics with a shared relay subscription. */
    fun observeEvents(topics: List<String>, ownSenderId: String): Flow<ChatEvent>

    /** Backward-compatible single-conversation observation used by the open chat screen. */
    fun observeEvents(topic: String, ownSenderId: String): Flow<ChatEvent> =
        observeEvents(listOf(topic), ownSenderId)
}
