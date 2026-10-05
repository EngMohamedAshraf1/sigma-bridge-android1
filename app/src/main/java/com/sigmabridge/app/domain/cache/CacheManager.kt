package com.sigmabridge.app.domain.cache

import com.sigmabridge.app.domain.model.TemporaryMediaFile
import com.sigmabridge.app.domain.model.TemporaryVoiceFile

/**
 * Sole owner of temporary voice/audio file locations. Business logic never
 * touches Context.cacheDir or java.io.File directly.
 */
interface CacheManager {

    /** Allocates a fresh UUID-named temp location for one voice/audio file. */
    fun createTempVoice(mimeType: String = "audio/ogg"): TemporaryVoiceFile

    /** Allocates a fresh temp location for non-audio media such as Telegram video. */
    fun createTempMedia(
        mimeType: String = "video/mp4",
        extension: String = "mp4"
    ): TemporaryMediaFile

    /** Deletes one previously-created voice/audio temp file. Safe if already gone. */
    fun delete(file: TemporaryVoiceFile)

    /** Deletes one previously-created non-audio media temp file. Safe if already gone. */
    fun delete(file: TemporaryMediaFile)

    /** Deletes every file left in the temp media cache, e.g. after a crash. */
    fun cleanup()
}
