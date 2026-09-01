package com.sigmabridge.app.domain.chat

/** Unified private-chat stream so messages and delivery receipts share one relay listener. */
sealed interface ChatEvent {
    data class Message(val message: ChatMessage) : ChatEvent
    data class Delivered(val receipt: ChatReceipt) : ChatEvent
}
