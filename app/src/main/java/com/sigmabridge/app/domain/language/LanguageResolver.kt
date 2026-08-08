package com.sigmabridge.app.domain.language

import com.sigmabridge.app.domain.model.LanguageConfiguration
import com.sigmabridge.app.domain.repository.LanguagePreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects which LanguageConfiguration applies for a given message — never
 * translates, never touches Gemini, never touches Telegram. Priority:
 * user > chat > global > LanguageConfiguration.DEFAULT.
 *
 * Known limitation, inherited from Phase 9.1's repository design (not
 * changed here — "no repository changes" is out of scope this phase):
 * getUser()/getChat()/getGlobal() each return LanguageConfiguration.DEFAULT
 * as their own "nothing configured for this scope" sentinel, not null.
 * That means a preference explicitly set to the exact same value as
 * DEFAULT is indistinguishable from "never configured" — this resolver
 * falls through to the next scope in that case too, since it has no way
 * to tell the two apart from what the repository returns. In practice
 * this only matters if someone deliberately picks the default pair as
 * their explicit override; the resolved value is still correct (DEFAULT),
 * just not distinguishably "theirs".
 */
@Singleton
class LanguageResolver @Inject constructor(
    private val languagePreferencesRepository: LanguagePreferencesRepository
) {
    suspend fun resolve(chatId: Long, userId: Long?): LanguageConfiguration {
        if (userId != null) {
            val userConfiguration = languagePreferencesRepository.getUser(userId)
            if (userConfiguration != LanguageConfiguration.DEFAULT) {
                return userConfiguration
            }
        }

        val chatConfiguration = languagePreferencesRepository.getChat(chatId)
        if (chatConfiguration != LanguageConfiguration.DEFAULT) {
            return chatConfiguration
        }

        val globalConfiguration = languagePreferencesRepository.getGlobal()
        if (globalConfiguration != LanguageConfiguration.DEFAULT) {
            return globalConfiguration
        }

        return LanguageConfiguration.DEFAULT
    }
}
