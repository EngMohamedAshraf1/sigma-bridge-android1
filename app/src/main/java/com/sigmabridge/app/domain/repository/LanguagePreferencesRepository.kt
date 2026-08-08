package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.LanguageConfiguration

/**
 * Three independent scopes, each with its own get/set — no precedence or
 * fallback logic between them lives here. Every getter transparently
 * returns LanguageConfiguration.DEFAULT until something has been
 * explicitly set for that exact scope (Phase 9.1 requirement: backward
 * compatibility with the current single-global-default behavior).
 *
 * Nothing in the app calls this yet — VoiceMessageHandler still always
 * uses LanguagePair.DEFAULT_MVP_PAIR directly. This interface is pure
 * infrastructure for a future phase.
 */
interface LanguagePreferencesRepository {
    suspend fun getGlobal(): LanguageConfiguration
    suspend fun setGlobal(configuration: LanguageConfiguration)

    suspend fun getChat(chatId: Long): LanguageConfiguration
    suspend fun setChat(chatId: Long, configuration: LanguageConfiguration)

    suspend fun getUser(userId: Long): LanguageConfiguration
    suspend fun setUser(userId: Long, configuration: LanguageConfiguration)
}
