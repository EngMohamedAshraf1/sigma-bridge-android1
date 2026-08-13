package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.language.LanguageCallback
import com.sigmabridge.app.domain.language.LanguageCallbackParser
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.language.LanguagePermissionChecker
import com.sigmabridge.app.domain.language.LanguageScope
import com.sigmabridge.app.domain.model.LanguageConfiguration
import com.sigmabridge.app.domain.model.TelegramKeyboard
import com.sigmabridge.app.domain.model.TelegramKeyboardButton
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.usecase.AnswerTelegramCallbackQueryUseCase
import com.sigmabridge.app.domain.usecase.EditTelegramMessageUseCase
import com.sigmabridge.app.domain.usecase.SetChatLanguageUseCase
import com.sigmabridge.app.domain.usecase.SetGlobalLanguageUseCase
import com.sigmabridge.app.domain.usecase.SetUserLanguageUseCase
import javax.inject.Inject

/**
 * Isolated from UpdateDispatcher on purpose (requirement 8): every piece of
 * Telegram-callback-specific logic — parsing callback_data, editing the
 * message in place, answering the callback query — lives here and only
 * here. UpdateDispatcher's own code never learned what a callback_query
 * even is; this handler is registered via the exact same @Binds @IntoSet
 * extension point VoiceMessageHandler/LanguageCommandHandler already use.
 *
 * Reuses the exact same Set*LanguageUseCase use cases and
 * LanguagePermissionChecker that LanguageCommandHandler already calls for
 * the text-command path (requirement 7) — no duplicated business logic, no
 * separate storage, no separate permission rules. This is a second front
 * end onto the identical backend; LanguageResolver/GeminiTranslationRepository/
 * VoiceMessageHandler have no idea this class exists.
 */
class LanguageCallbackHandler @Inject constructor(
    private val callbackParser: LanguageCallbackParser,
    private val permissionChecker: LanguagePermissionChecker,
    private val setGlobalLanguage: SetGlobalLanguageUseCase,
    private val setChatLanguage: SetChatLanguageUseCase,
    private val setUserLanguage: SetUserLanguageUseCase,
    private val editTelegramMessage: EditTelegramMessageUseCase,
    private val answerCallbackQuery: AnswerTelegramCallbackQueryUseCase
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean =
        callbackParser.parse(update.callbackData) != null

    override suspend fun handle(update: TelegramUpdate) {
        val callback = callbackParser.parse(update.callbackData) ?: return
        val callbackQueryId = update.callbackQueryId ?: return

        // Acknowledge immediately - required by Telegram to stop the tapped button's spinner,
        // regardless of what happens next.
        answerCallbackQuery(callbackQueryId)

        val senderId = update.senderUserId
        if (senderId == null) {
            editTelegramMessage(update.chatId, update.messageId, "I couldn't identify who tapped that button.")
            return
        }

        when (callback) {
            is LanguageCallback.SelectScope -> handleSelectScope(update, senderId, callback.scope)
            is LanguageCallback.SelectSource -> showTargetSelection(update, callback.scope, callback.sourceCode)
            is LanguageCallback.SelectTarget -> handleSelectTarget(update, senderId, callback)
        }
    }

    private suspend fun handleSelectScope(update: TelegramUpdate, senderId: Long, scope: LanguageScope) {
        if (!isAuthorized(scope, update.chatId, senderId)) {
            editTelegramMessage(update.chatId, update.messageId, deniedText(scope))
            return
        }
        editTelegramMessage(
            chatId = update.chatId,
            messageId = update.messageId,
            text = "Choose Source Language\n\n(${scope.label})",
            keyboard = languageSelectionKeyboard { code -> LanguageCallbackParser.sourceData(scope, code) }
        )
    }

    private suspend fun showTargetSelection(update: TelegramUpdate, scope: LanguageScope, sourceCode: String) {
        // Permission was already checked when the scope was picked (previous step); a source
        // code embedded in callback_data carries no new authorization implication, so no
        // re-check here - same trust boundary the text-command flow uses in one shot.
        val sourceName = LanguageCatalog.findByCode(sourceCode)?.displayName ?: sourceCode
        editTelegramMessage(
            chatId = update.chatId,
            messageId = update.messageId,
            text = "Choose Target Language\n\nSource: $sourceName",
            keyboard = languageSelectionKeyboard { code -> LanguageCallbackParser.targetData(scope, sourceCode, code) }
        )
    }

    private suspend fun handleSelectTarget(update: TelegramUpdate, senderId: Long, callback: LanguageCallback.SelectTarget) {
        if (!isAuthorized(callback.scope, update.chatId, senderId)) {
            editTelegramMessage(update.chatId, update.messageId, deniedText(callback.scope))
            return
        }

        val source = LanguageCatalog.findByCode(callback.sourceCode)
        val target = LanguageCatalog.findByCode(callback.targetCode)
        if (source == null || target == null) {
            editTelegramMessage(update.chatId, update.messageId, "That language is no longer supported. Please start again with /language.")
            return
        }

        val configuration = LanguageConfiguration(source = source, target = target)
        when (callback.scope) {
            LanguageScope.USER -> setUserLanguage(update.chatId, senderId, configuration)
            LanguageScope.CHAT -> setChatLanguage(update.chatId, configuration)
            LanguageScope.GLOBAL -> setGlobalLanguage(configuration)
        }

        editTelegramMessage(
            chatId = update.chatId,
            messageId = update.messageId,
            text = "Language updated successfully.\n\n" +
                "Scope: ${callback.scope.label}\n" +
                "Translation: ${source.displayName} \u2192 ${target.displayName}\n\n" +
                "This will affect future translations only."
        )
    }

    /** Exactly the same three rules LanguageCommandHandler enforces for /language set - no new permission logic. */
    private suspend fun isAuthorized(scope: LanguageScope, chatId: Long, senderId: Long): Boolean = when (scope) {
        LanguageScope.USER -> permissionChecker.authorizeUserChange(senderId, senderId)
        LanguageScope.CHAT -> permissionChecker.authorizeChatChange(chatId, senderId)
        LanguageScope.GLOBAL -> permissionChecker.authorizeGlobalChange(senderId)
    }

    private fun deniedText(scope: LanguageScope): String = when (scope) {
        LanguageScope.USER -> "You can only change your own language."
        LanguageScope.CHAT -> "Only group administrators can change the chat language."
        LanguageScope.GLOBAL -> "Only the bot owner can change the global language."
    }

    /** Requirement 3: every button comes directly from LanguageCatalog.ALL — no separate language list maintained here. */
    private fun languageSelectionKeyboard(callbackDataFor: (String) -> String): TelegramKeyboard {
        val buttons = LanguageCatalog.ALL.map { language ->
            TelegramKeyboardButton(
                text = "${flagFor(language.code)} ${language.displayName}",
                callbackData = callbackDataFor(language.code)
            )
        }
        return TelegramKeyboard(rows = buttons.chunked(2))
    }

    /**
     * Cosmetic only — flags never decide which languages are valid or
     * exist; that is entirely LanguageCatalog's job. A catalog code with no
     * mapped flag here still gets a full, working button, just with a
     * generic globe icon instead of a country flag.
     */
    private fun flagFor(code: String): String = FLAGS[code] ?: "\uD83C\uDF10"

    private companion object {
        val FLAGS: Map<String, String> = mapOf(
            "en" to "\uD83C\uDDEC\uD83C\uDDE7",
            "ru" to "\uD83C\uDDF7\uD83C\uDDFA",
            "ar" to "\uD83C\uDDF8\uD83C\uDDE6",
            "fr" to "\uD83C\uDDEB\uD83C\uDDF7",
            "de" to "\uD83C\uDDE9\uD83C\uDDEA",
            "es" to "\uD83C\uDDEA\uD83C\uDDF8",
            "it" to "\uD83C\uDDEE\uD83C\uDDF9",
            "pt" to "\uD83C\uDDF5\uD83C\uDDF9",
            "tr" to "\uD83C\uDDF9\uD83C\uDDF7",
            "zh" to "\uD83C\uDDE8\uD83C\uDDF3",
            "ja" to "\uD83C\uDDEF\uD83C\uDDF5",
            "ko" to "\uD83C\uDDF0\uD83C\uDDF7",
            "hi" to "\uD83C\uDDEE\uD83C\uDDF3",
            "uk" to "\uD83C\uDDFA\uD83C\uDDE6",
            "pl" to "\uD83C\uDDF5\uD83C\uDDF1",
            "auto" to "\uD83C\uDF10"
        )
    }
}
