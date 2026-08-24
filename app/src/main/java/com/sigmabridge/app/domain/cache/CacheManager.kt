package com.sigmabridge.app.domain.cache

import com.sigmabridge.app.domain.model.TemporaryVoiceFile

/**
 * Sole owner of temporary voice/audio file locations. Business logic never
 * touches Context.cacheDir or java.io.File directly.
 */
interface CacheManager {

    /** Allocates a fresh UUID-named temp location for one voice/audio file. */
    fun createTempVoice(mimeType: String = "audio/ogg"): TemporaryVoiceFile

    /** Deletes one previously-created temp file. Safe to call if already gone. */
    fun delete(file: TemporaryVoiceFile)

    /** Deletes every file left in the temp media cache, e.g. after a crash. */
    fun cleanup()
}
