package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.TelegramKeyboard
import com.sigmabridge.app.domain.repository.TelegramRepository
import javax.inject.Inject

/** The only allowed path to TelegramRepository.editMessage() — same discipline as SendTelegramMessageUseCase. */
class EditTelegramMessageUseCase @Inject constructor(
    private val telegramRepository: TelegramRepository
) {
    suspend operator fun invoke(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: TelegramKeyboard? = null
    ): Result<Unit> = telegramRepository.editMessage(chatId, messageId, text, keyboard)
}
