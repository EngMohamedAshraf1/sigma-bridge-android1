package com.sigmabridge.app.domain.cache

import com.sigmabridge.app.domain.model.TemporaryVoiceFile

/**
 * Sole owner of "where do temporary voice files live on disk". Business
 * logic (repositories, use cases) never touches Context.cacheDir or
 * java.io.File directly — it asks this abstraction for a location and
 * hands cleanup back to it. Exactly one implementation (FileCacheManager,
 * data layer) is allowed to actually touch the filesystem's cache directory.
 */
interface CacheManager {

    /** Allocates a new temp location for one voice file, named with a fresh UUID — never the Telegram file_id. */
    fun createTempVoice(): TemporaryVoiceFile

    /** Deletes one previously-created temp file. Safe to call if it's already gone. */
    fun delete(file: TemporaryVoiceFile)

    /** Deletes every file left in the temp voice cache — orphan cleanup (e.g. after a crash mid-pipeline). */
    fun cleanup()
}
