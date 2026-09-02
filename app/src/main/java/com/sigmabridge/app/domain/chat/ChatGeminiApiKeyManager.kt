package com.sigmabridge.app.domain.chat

import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat-only Gemini key selector. It reads the same configured API-key list as
 * Telegram, but keeps its own cursor and invalid-key state so Private Chat can
 * never mutate Telegram's Gemini key state.
 */
@Singleton
class ChatGeminiApiKeyManager @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val mutex = Mutex()
    private var cursor = 0
    private val invalidKeys = mutableSetOf<String>()

    suspend fun totalKeyCount(): Int = settingsRepository.geminiApiKeys.first().size

    suspend fun nextKey(): String? = mutex.withLock {
        val keys = settingsRepository.geminiApiKeys.first()
        if (keys.isEmpty()) return@withLock null

        val start = cursor % keys.size
        var index = start
        do {
            val candidate = keys[index]
            index = (index + 1) % keys.size
            if (candidate !in invalidKeys) {
                cursor = index
                return@withLock candidate
            }
        } while (index != start)

        null
    }

    suspend fun markInvalid(key: String) = mutex.withLock {
        invalidKeys += key
    }
}
