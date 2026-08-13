package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.LanguageConfiguration

/**
 * Three independent scopes, each with its own get/set — no precedence or
 * fallback logic between them lives here. Every getter transparently
 * returns LanguageConfiguration.DEFAULT until something has been
 * explicitly set for that exact scope.
 *
 * Phase 9.7: "user" is scoped by (chatId, userId), not userId alone — the
 * same person can have a different language in different chats. Private
 * chats need no special case: chatId there is just the private chat's own
 * id, so the same (chatId, userId) key scheme applies uniformly.
 */
interface LanguagePreferencesRepository {
    suspend fun getGlobal(): LanguageConfiguration
    suspend fun setGlobal(configuration: LanguageConfiguration)

    suspend fun getChat(chatId: Long): LanguageConfiguration
    suspend fun setChat(chatId: Long, configuration: LanguageConfiguration)

    suspend fun getUser(chatId: Long, userId: Long): LanguageConfiguration
    suspend fun setUser(chatId: Long, userId: Long, configuration: LanguageConfiguration)
}
