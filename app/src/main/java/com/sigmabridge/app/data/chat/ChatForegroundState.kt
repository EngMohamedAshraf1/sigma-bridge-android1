package com.sigmabridge.app.data.chat

/**
 * Process-local state for the currently open Private Chat conversation.
 * Used only to prevent the background notification worker from creating
 * unread notifications for a conversation the user is actively viewing.
 */
object ChatForegroundState {
    @Volatile
    var openPartnerId: String? = null
}
