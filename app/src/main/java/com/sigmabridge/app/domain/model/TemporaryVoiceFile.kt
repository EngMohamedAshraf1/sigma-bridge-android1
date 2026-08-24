package com.sigmabridge.app.domain.model

/**
 * A downloaded Telegram voice/audio file sitting in local temp storage.
 * [id] is the UUID CacheManager generated for it — deliberately NOT the
 * Telegram file_id. [path] is the absolute filesystem path. [mimeType]
 * carries the exact Gemini-supported media type to use when uploading the
 * bytes; Voice keeps its existing audio/ogg default.
 */
data class TemporaryVoiceFile(
    val id: String,
    val path: String,
    val mimeType: String = "audio/ogg"
)
