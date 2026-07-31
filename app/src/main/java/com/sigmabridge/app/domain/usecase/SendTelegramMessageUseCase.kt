package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.repository.TelegramRepository
import javax.inject.Inject

/**
 * The only allowed path to TelegramRepository.sendMessage(). No UI and no
 * Service is permitted to call the repository's sendMessage directly —
 * only this use case, and only handlers in domain/dispatch (VoiceMessageHandler
 * today) are expected to call it.
 */
class SendTelegramMessageUseCase @Inject constructor(
    private val telegramRepository: TelegramRepository
) {
    suspend operator fun invoke(chatId: Long, text: String): Result<Unit> =
        telegramRepository.sendMessage(chatId, text)
}
