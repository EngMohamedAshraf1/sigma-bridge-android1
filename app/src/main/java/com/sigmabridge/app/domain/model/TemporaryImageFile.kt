package com.sigmabridge.app.domain.model

/** A downloaded Telegram photo stored temporarily for the independent image path. */
data class TemporaryImageFile(
    val id: String,
    val path: String,
    val mimeType: String = "image/jpeg"
)
