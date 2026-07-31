package com.sigmabridge.app.domain.model

/**
 * A downloaded voice message sitting in local temp storage. [id] is the
 * UUID CacheManager generated for it — deliberately NOT the Telegram
 * file_id, so nothing on disk can be correlated back to a specific
 * Telegram file. [path] is an absolute filesystem path; only CacheManager
 * and the download/translation data classes that write to it deal with it
 * as an actual java.io.File — everything else in the app passes this
 * value object around instead of a raw File.
 */
data class TemporaryVoiceFile(
    val id: String,
    val path: String
)
