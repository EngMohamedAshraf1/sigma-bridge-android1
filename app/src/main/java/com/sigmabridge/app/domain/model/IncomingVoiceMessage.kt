package com.sigmabridge.app.domain.model

/** A voice message that has been recognized and downloaded, ready for translation in Phase 5/6. */
data class IncomingVoiceMessage(
    val chatId: Long,
    val voiceFile: TemporaryVoiceFile
)
