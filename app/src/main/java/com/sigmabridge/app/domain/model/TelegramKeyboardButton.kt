package com.sigmabridge.app.domain.model

/** One tappable button. [callbackData] is what comes back in the next callback_query update - kept short (Telegram caps it at 64 bytes). */
data class TelegramKeyboardButton(
    val text: String,
    val callbackData: String
)
