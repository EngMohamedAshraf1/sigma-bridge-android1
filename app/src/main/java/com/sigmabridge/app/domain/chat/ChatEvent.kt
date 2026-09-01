package com.sigmabridge.app.domain.chat

/** Unified private-chat stream for messages plus delivery/read receipts. */
sealed interface ChatEvent {
    data class Message(val message: ChatMessage) : ChatEvent
    data class Delivered(val receipt: ChatReceipt) : ChatEvent
    data class Read(val receipt: ChatReceipt) : ChatEvent
}
