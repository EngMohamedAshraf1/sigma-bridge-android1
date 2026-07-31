package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.IncomingVoiceMessage
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.TelegramRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

/**
 * Phase 4 scope only: recognize a voice message in TelegramRepository's raw
 * update stream and download it to local temp storage via
 * DownloadRepository. Nothing consumes [IncomingVoiceMessage] yet — no
 * translation call, no reply, no Foreground Service driving this. Those are
 * Phase 5/6/7. A download failure silently drops that one update for now;
 * Phase 6 is where failures get surfaced back to the user (mirroring
 * handlers.py's try/except/finally), since only then does a "reply to the
 * user" concept exist in this app.
 */
class ObserveIncomingVoiceUseCase @Inject constructor(
    private val telegramRepository: TelegramRepository,
    private val downloadRepository: DownloadRepository
) {
    operator fun invoke(): Flow<IncomingVoiceMessage> =
        telegramRepository.updates.mapNotNull { update ->
            val fileId = update.voiceFileId ?: return@mapNotNull null
            downloadRepository.downloadVoice(fileId).getOrNull()?.let { voiceFile ->
                IncomingVoiceMessage(chatId = update.chatId, voiceFile = voiceFile)
            }
        }
}
