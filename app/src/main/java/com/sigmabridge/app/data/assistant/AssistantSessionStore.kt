package com.sigmabridge.app.data.assistant

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AssistantConversationTurn(
    val role: String,
    val content: String,
    val createdAtEpochMs: Long
)

/**
 * Local-only, shared-per-Telegram-chat assistant context.
 * Each turn expires after 24 hours and the retained context is bounded.
 * Gemini requests set store=false, so server-side interaction history isn't used.
 */
@Singleton
class AssistantSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        pruneExpiredSessions()
    }

    @Synchronized
    fun history(chatId: Long): List<AssistantConversationTurn> =
        readFreshTurns(chatId.toString(), System.currentTimeMillis())

    @Synchronized
    fun remember(chatId: Long, userMessage: String, assistantMessage: String) {
        val now = System.currentTimeMillis()
        val turns = readFreshTurns(chatId.toString(), now).toMutableList()
        turns += AssistantConversationTurn("user", userMessage, now)
        turns += AssistantConversationTurn("assistant", assistantMessage, now)
        val retained = turns.filter { now >= it.createdAtEpochMs && now - it.createdAtEpochMs < SESSION_TTL_MS }
            .takeLast(MAX_CONTEXT_MESSAGES)
        preferences.edit()
            .putString(chatId.toString(), json.encodeToString(retained))
            .commit()
    }

    @Synchronized
    fun clear(chatId: Long) {
        preferences.edit().remove(chatId.toString()).commit()
    }

    @Synchronized
    private fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        preferences.all.keys.toList().forEach { key ->
            readFreshTurns(key, now)
        }
    }

    private fun readFreshTurns(key: String, now: Long): List<AssistantConversationTurn> {
        val stored = preferences.getString(key, null) ?: return emptyList()
        val decoded = runCatching {
            json.decodeFromString<List<AssistantConversationTurn>>(stored)
        }.getOrDefault(emptyList())
        val retained = decoded.filter { now >= it.createdAtEpochMs && now - it.createdAtEpochMs < SESSION_TTL_MS }
            .takeLast(MAX_CONTEXT_MESSAGES)
        if (retained != decoded) {
            if (retained.isEmpty()) {
                preferences.edit().remove(key).commit()
            } else {
                preferences.edit().putString(key, json.encodeToString(retained)).commit()
            }
        }
        return retained
    }

    private companion object {
        const val PREFERENCES_NAME = "sigma_bridge_assistant_sessions"
        const val SESSION_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_CONTEXT_MESSAGES = 16
    }
}
