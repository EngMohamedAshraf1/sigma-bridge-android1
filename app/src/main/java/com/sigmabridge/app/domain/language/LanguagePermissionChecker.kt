package com.sigmabridge.app.domain.language

/**
 * Nothing about "who is allowed to do what" is hardcoded in
 * LanguageCommandHandler — it only calls these three questions and acts on
 * the answer. The one implementation (TelegramLanguagePermissionChecker,
 * data layer) is where the actual rules live: owner-only for global,
 * Telegram chat-admin for chat, self-only for user.
 */
interface LanguagePermissionChecker {
    /**
     * True if [userId] may change the global language. If no owner has
     * been claimed yet, this call itself claims ownership for [userId] and
     * returns true — see OwnerRepository.
     */
    suspend fun authorizeGlobalChange(userId: Long): Boolean

    /** True if [userId] is a Telegram administrator (or creator) of [chatId]. */
    suspend fun authorizeChatChange(chatId: Long, userId: Long): Boolean

    /** True only if [requestingUserId] == [targetUserId] — nobody may change another person's own preference. */
    suspend fun authorizeUserChange(requestingUserId: Long, targetUserId: Long): Boolean
}
