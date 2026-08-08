package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.domain.language.LanguagePermissionChecker
import com.sigmabridge.app.domain.repository.OwnerRepository
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramLanguagePermissionChecker @Inject constructor(
    private val telegramApiClient: TelegramApiClient,
    private val settingsRepository: SettingsRepository,
    private val ownerRepository: OwnerRepository
) : LanguagePermissionChecker {

    override suspend fun authorizeGlobalChange(userId: Long): Boolean {
        if (ownerRepository.claimOwnershipIfUnset(userId)) return true
        return ownerRepository.getOwnerUserId() == userId
    }

    override suspend fun authorizeChatChange(chatId: Long, userId: Long): Boolean {
        val token = settingsRepository.botToken.first() ?: return false
        val administratorIds = runCatching {
            telegramApiClient.getChatAdministrators(token, chatId)
        }.getOrDefault(emptyList())
        return userId in administratorIds
    }

    override suspend fun authorizeUserChange(requestingUserId: Long, targetUserId: Long): Boolean =
        requestingUserId == targetUserId
}
