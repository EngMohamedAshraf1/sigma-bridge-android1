package com.sigmabridge.app.domain.usecase

import com.sigmabridge.app.domain.gemini.GeminiApiKeyManager
import com.sigmabridge.app.domain.model.GeminiKeyInfo
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * SettingsViewModel's own documented convention is to talk only to
 * use cases, never to a repository/manager directly (see
 * ObserveStoredCredentialsUseCase/SaveSettingsUseCase) — this keeps that
 * consistent for the new live key-status observation.
 */
class ObserveGeminiKeyStatusesUseCase @Inject constructor(
    private val geminiApiKeyManager: GeminiApiKeyManager
) {
    operator fun invoke(): Flow<List<GeminiKeyInfo>> = geminiApiKeyManager.keyInfos
}
