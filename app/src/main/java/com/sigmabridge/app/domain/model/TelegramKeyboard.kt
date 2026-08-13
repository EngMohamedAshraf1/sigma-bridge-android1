package com.sigmabridge.app.domain.model

/** An inline keyboard as rows of buttons. Domain-level shape only — TelegramApiClient (data layer) maps this to Telegram's actual inline_keyboard JSON. */
data class TelegramKeyboard(
    val rows: List<List<TelegramKeyboardButton>>
) {
    companion object {
        /** Convenience for the common case of one button per row (a vertical list of buttons). */
        fun singleColumn(buttons: List<TelegramKeyboardButton>): TelegramKeyboard =
            TelegramKeyboard(rows = buttons.map { listOf(it) })
    }
}
