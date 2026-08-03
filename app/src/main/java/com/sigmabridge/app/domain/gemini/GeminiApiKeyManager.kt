package com.sigmabridge.app.domain.gemini

import com.sigmabridge.app.domain.model.GeminiKeyInfo
import com.sigmabridge.app.domain.model.GeminiKeyStatus
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when every configured Gemini key is exhausted or invalid — a clear, typed failure instead of a crash or a silent hang. */
class NoAvailableGeminiKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns key selection only — it has no idea what a 429 or a 401 response
 * looks like; GeminiTranslationRepository decides that and calls
 * [markInvalid]/[markQuotaExceeded]/[markSucceeded] when appropriate. This
 * class just answers "what's the next key to try" deterministically and
 * "is this key still usable" — the exact same responsibility as Phase 8.4.
 *
 * Phase 8.4 extension (still this class, no redesign): [keyInfos] exposes a
 * reactive, per-key status for the Settings UI. The round-robin algorithm
 * itself — [nextKey]'s cursor advancement, [markInvalid]'s permanent
 * session-scoped exclusion, [totalKeyCount]'s bound — is byte-for-byte
 * unchanged from Phase 8.4. Only bookkeeping was added: an in-memory
 * status overlay, updated at the exact same mutation points that already
 * existed, plus two new hooks (quota/success) that GeminiTranslationRepository
 * now also calls at points it already distinguishes internally.
 *
 * Invalid keys are tracked in memory only, for the lifetime of this
 * @Singleton (i.e. the process) — "do not reuse it again during the
 * current session" per the Phase 8.4 requirement. They are never removed
 * from persistent storage; a fresh process restart gives every stored key
 * another chance, since a 401 today doesn't necessarily mean the key is
 * permanently dead (e.g. it could have been temporarily rotated).
 *
 * Thread-safety: translate() could in principle be invoked concurrently
 * (the real pipeline and the internal Gemini test screen both call it), so
 * cursor/invalid-set/status mutations are guarded by a Mutex.
 */
@Singleton
class GeminiApiKeyManager @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val mutex = Mutex()
    private var cursor = 0
    private val invalidKeys = mutableSetOf<String>()

    // In-memory overlay of non-default statuses only; a key with no entry here is READY.
    // Combined with the authoritative stored key order below, so a newly added/removed key
    // (via Settings) is reflected immediately, without waiting for the next translate() call.
    private val _statusOverlay = MutableStateFlow<Map<String, GeminiKeyStatus>>(emptyMap())

    /** Reactive, ordered, per-key status for the Settings UI (Phase 8.4 extension). */
    val keyInfos: Flow<List<GeminiKeyInfo>> = combine(
        settingsRepository.geminiApiKeys,
        _statusOverlay
    ) { keys, overlay ->
        keys.map { key -> GeminiKeyInfo(key = key, status = overlay[key] ?: GeminiKeyStatus.READY) }
    }

    /** Total number of keys ever configured, regardless of invalid status — the correct bound for "try each key at most once per translation". */
    suspend fun totalKeyCount(): Int = settingsRepository.geminiApiKeys.first().size

    /**
     * Deterministically returns the next non-invalid key, advancing a
     * round-robin cursor over the full stored order so repeated calls keep
     * moving forward instead of always retrying the same key first. Never
     * random. Returns null if no key is configured, or every configured
     * key has been marked invalid this session.
     */
    suspend fun nextKey(): String? = mutex.withLock {
        val allKeys = settingsRepository.geminiApiKeys.first()
        if (allKeys.isEmpty()) return@withLock null

        val startIndex = cursor % allKeys.size
        var index = startIndex
        do {
            val candidate = allKeys[index]
            index = (index + 1) % allKeys.size
            if (candidate !in invalidKeys) {
                cursor = index
                promoteToActive(candidate)
                return@withLock candidate
            }
        } while (index != startIndex)

        null
    }

    /** Marks [key] unusable for the rest of this process's lifetime (a 401/403 response). */
    suspend fun markInvalid(key: String) = mutex.withLock {
        invalidKeys += key
        setStatus(key, GeminiKeyStatus.INVALID)
    }

    /** Records a 429 for [key] — still selectable again later (quotas can reset), just reflected in the UI. */
    suspend fun markQuotaExceeded(key: String) = mutex.withLock {
        setStatus(key, GeminiKeyStatus.QUOTA_EXCEEDED)
    }

    /** Clears any stale QUOTA_EXCEEDED mark once [key] is used successfully. */
    suspend fun markSucceeded(key: String) = mutex.withLock {
        setStatus(key, GeminiKeyStatus.ACTIVE)
    }

    /** Exactly one key is ever ACTIVE: demote whichever key held that status before promoting [key]. */
    private fun promoteToActive(key: String) {
        val current = _statusOverlay.value.toMutableMap()
        current.entries.removeAll { it.value == GeminiKeyStatus.ACTIVE }
        current[key] = GeminiKeyStatus.ACTIVE
        _statusOverlay.value = current
    }

    private fun setStatus(key: String, status: GeminiKeyStatus) {
        _statusOverlay.value = _statusOverlay.value + (key to status)
    }
}
