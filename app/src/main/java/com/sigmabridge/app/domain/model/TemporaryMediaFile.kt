package com.sigmabridge.app.domain.model

/**
 * A downloaded non-audio Telegram media file stored temporarily on disk.
 */
data class TemporaryMediaFile(
    val id: String,
    val path: String,
    val mimeType: String = "video/mp4"
)
