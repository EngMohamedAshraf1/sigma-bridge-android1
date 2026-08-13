package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.repository.TelegramRepository
import javax.inject.Inject

/** The only allowed path to TelegramRepository.answerCallbackQuery() — same discipline as SendTelegramMessageUseCase. */
class AnswerTelegramCallbackQueryUseCase @Inject constructor(
    private val telegramRepository: TelegramRepository
) {
    suspend operator fun invoke(callbackQueryId: String): Result<Unit> =
        telegramRepository.answerCallbackQuery(callbackQueryId)
}
