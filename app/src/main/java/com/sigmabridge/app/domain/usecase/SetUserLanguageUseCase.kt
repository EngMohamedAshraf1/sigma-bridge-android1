package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.LanguageConfiguration
import com.sigmabridge.app.domain.repository.LanguagePreferencesRepository
import javax.inject.Inject

/** Phase 9.7: "user" is scoped by (chatId, userId) - the same person can have a different language per chat. */
class SetUserLanguageUseCase @Inject constructor(
    private val languagePreferencesRepository: LanguagePreferencesRepository
) {
    suspend operator fun invoke(chatId: Long, userId: Long, configuration: LanguageConfiguration) =
        languagePreferencesRepository.setUser(chatId, userId, configuration)
}
