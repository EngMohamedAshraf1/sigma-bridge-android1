package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.LanguageConfiguration
import com.sigmabridge.app.domain.repository.LanguagePreferencesRepository
import javax.inject.Inject

/** Unused for now — pure infrastructure for a future phase (Phase 9.1 requirement 5). */
class SetGlobalLanguageUseCase @Inject constructor(
    private val languagePreferencesRepository: LanguagePreferencesRepository
) {
    suspend operator fun invoke(configuration: LanguageConfiguration) =
        languagePreferencesRepository.setGlobal(configuration)
}
