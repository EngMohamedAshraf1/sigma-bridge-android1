package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.model.StoredCredentials
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Read side of settings. SettingsViewModel calls this instead of reading
 * SettingsRepository.botToken/geminiApiKey directly — the ViewModel talks
 * to the domain layer only, never to a repository.
 */
class ObserveStoredCredentialsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<StoredCredentials> =
        combine(settingsRepository.botToken, settingsRepository.geminiApiKey) { token, key ->
            StoredCredentials(botToken = token, geminiApiKey = key)
        }
}
