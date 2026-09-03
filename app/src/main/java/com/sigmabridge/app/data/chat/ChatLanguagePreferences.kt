package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local-only language preference for Private Chat. Telegram does not use this store. */
@Singleton
class ChatLanguagePreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTargetLanguage(): Language =
        LanguageCatalog.findByCode(preferences.getString(KEY_TARGET_LANGUAGE, null).orEmpty())
            ?: LanguageCatalog.ARABIC

    fun setTargetLanguage(language: Language) {
        preferences.edit().putString(KEY_TARGET_LANGUAGE, language.code).apply()
    }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_private_chat_language"
        const val KEY_TARGET_LANGUAGE = "translation_target_language"
    }
}
