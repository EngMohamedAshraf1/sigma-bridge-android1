package com.sigmabridge.app.domain.model

/**
 * Mirrors Telegram's own chat.type string. Not used for any language
 * decision in Phase 9.0 (LanguagePair.DEFAULT_MVP_PAIR stays the single
 * global default for every chat type) — captured now so a future phase
 * (per-group language) can read it off TelegramUpdate without touching the
 * Telegram parsing/dispatch layer again.
 */
enum class TelegramChatType {
    PRIVATE,
    GROUP,
    SUPERGROUP,
    CHANNEL,
    UNKNOWN
}
