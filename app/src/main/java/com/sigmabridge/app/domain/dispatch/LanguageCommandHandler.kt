package com.sigmabridge.app.domain.dispatch

import com.sigmabridge.app.domain.language.LanguageCommand
import com.sigmabridge.app.domain.language.LanguageCommandParser
import com.sigmabridge.app.domain.language.LanguagePermissionChecker
import com.sigmabridge.app.domain.language.LanguageResolver
import com.sigmabridge.app.domain.model.Language
import com.sigmabridge.app.domain.model.LanguageConfiguration
import com.sigmabridge.app.domain.model.TelegramUpdate
import com.sigmabridge.app.domain.usecase.GetChatLanguageUseCase
import com.sigmabridge.app.domain.usecase.GetGlobalLanguageUseCase
import com.sigmabridge.app.domain.usecase.GetUserLanguageUseCase
import com.sigmabridge.app.domain.usecase.SendTelegramMessageUseCase
import com.sigmabridge.app.domain.usecase.SetChatLanguageUseCase
import com.sigmabridge.app.domain.usecase.SetGlobalLanguageUseCase
import com.sigmabridge.app.domain.usecase.SetUserLanguageUseCase
import javax.inject.Inject

/**
 * The first user-visible language feature (Phase 9.3). Registered with
 * UpdateDispatcher via DispatchModule's @IntoSet, exactly like
 * VoiceMessageHandler — the dispatcher's own code is untouched; this is
 * its intended extension point.
 *
 * This class only reads/writes preferences and replies — it never builds a
 * TranslationRequest, never calls TranslationRepository, never touches
 * LanguageResolver's resolution logic (only calls resolve() to display the
 * "Effective" value in /language's summary). The next voice message picks
 * up any change automatically, purely because VoiceMessageHandler already
 * asks LanguageResolver fresh on every call (Phase 9.2) — no wiring needed
 * here for that to work.
 *
 * Phase 9.4: every command shape, every parsing rule, and every permission
 * rule is byte-for-byte unchanged from Phase 9.3 — only the text returned
 * to the user changed. "Not configured" is printed whenever a scope's
 * getter returns LanguageConfiguration.DEFAULT — the same sentinel-based
 * "nothing set for this scope" signal Phase 9.1/9.2/9.3 already relied on,
 * just made visible in the reply instead of being silently invisible.
 */
class LanguageCommandHandler @Inject constructor(
    private val commandParser: LanguageCommandParser,
    private val permissionChecker: LanguagePermissionChecker,
    private val getGlobalLanguage: GetGlobalLanguageUseCase,
    private val setGlobalLanguage: SetGlobalLanguageUseCase,
    private val getChatLanguage: GetChatLanguageUseCase,
    private val setChatLanguage: SetChatLanguageUseCase,
    private val getUserLanguage: GetUserLanguageUseCase,
    private val setUserLanguage: SetUserLanguageUseCase,
    private val languageResolver: LanguageResolver,
    private val sendTelegramMessage: SendTelegramMessageUseCase
) : UpdateHandler {

    override fun canHandle(update: TelegramUpdate): Boolean =
        commandParser.parse(update.messageText) != null

    override suspend fun handle(update: TelegramUpdate) {
        val command = commandParser.parse(update.messageText) ?: return
        val senderId = update.senderUserId
        if (senderId == null) {
            reply(update, "I couldn't identify who sent that command.")
            return
        }

        when (command) {
            is LanguageCommand.ShowAll -> handleShowAll(update, senderId)
            is LanguageCommand.ShowGlobal -> reply(update, "Global language\n\n${getGlobalLanguage().describeOrUnset()}")
            is LanguageCommand.ShowUser -> reply(update, "Your language\n\n${getUserLanguage(senderId).describeOrUnset()}")
            is LanguageCommand.ShowChat -> reply(update, "Chat language\n\n${getChatLanguage(update.chatId).describeOrUnset()}")
            is LanguageCommand.SetGlobal -> handleSetGlobal(update, senderId, command)
            is LanguageCommand.SetChat -> handleSetChat(update, senderId, command)
            is LanguageCommand.SetUser -> handleSetUser(update, senderId, command)
            is LanguageCommand.Unrecognized -> reply(update, HELP_TEXT)
        }
    }

    private suspend fun handleShowAll(update: TelegramUpdate, senderId: Long) {
        val user = getUserLanguage(senderId)
        val chat = getChatLanguage(update.chatId)
        val global = getGlobalLanguage()
        val effective = languageResolver.resolve(chatId = update.chatId, userId = senderId)
        reply(
            update,
            "Current configuration\n\n" +
                "Global:\n${global.describeOrUnset()}\n\n" +
                "Chat:\n${chat.describeOrUnset()}\n\n" +
                "You:\n${user.describeOrUnset()}\n\n" +
                "Effective:\n${effective.describe()}"
        )
    }

    private suspend fun handleSetGlobal(update: TelegramUpdate, senderId: Long, command: LanguageCommand.SetGlobal) {
        if (!permissionChecker.authorizeGlobalChange(senderId)) {
            reply(update, "Only the bot owner can change the global language.")
            return
        }
        val configuration = parseConfiguration(update, command.sourceCode, command.targetCode) ?: return
        setGlobalLanguage(configuration)
        reply(update, confirmationText(scope = "Global", configuration = configuration))
    }

    private suspend fun handleSetChat(update: TelegramUpdate, senderId: Long, command: LanguageCommand.SetChat) {
        if (!permissionChecker.authorizeChatChange(update.chatId, senderId)) {
            reply(update, "Only group administrators can change the chat language.")
            return
        }
        val configuration = parseConfiguration(update, command.sourceCode, command.targetCode) ?: return
        setChatLanguage(update.chatId, configuration)
        reply(update, confirmationText(scope = "Chat", configuration = configuration))
    }

    private suspend fun handleSetUser(update: TelegramUpdate, senderId: Long, command: LanguageCommand.SetUser) {
        // Always true today (requesting == target - there is no "/language set user <otherUserId> ..." syntax),
        // kept behind the same abstraction as the other two for uniformity, not because it can fail right now.
        if (!permissionChecker.authorizeUserChange(senderId, senderId)) {
            reply(update, "You can only change your own language.")
            return
        }
        val configuration = parseConfiguration(update, command.sourceCode, command.targetCode) ?: return
        setUserLanguage(senderId, configuration)
        reply(update, confirmationText(scope = "User", configuration = configuration))
    }

    private suspend fun parseConfiguration(update: TelegramUpdate, sourceCode: String, targetCode: String): LanguageConfiguration? {
        val source = Language.SUPPORTED.firstOrNull { it.code.equals(sourceCode, ignoreCase = true) }
        val target = Language.SUPPORTED.firstOrNull { it.code.equals(targetCode, ignoreCase = true) }
        if (source == null || target == null) {
            val supportedCodes = Language.SUPPORTED.joinToString("\n") { it.code }
            reply(update, "Unknown language code.\n\nSupported languages:\n$supportedCodes")
            return null
        }
        return LanguageConfiguration(source = source, target = target)
    }

    private suspend fun reply(update: TelegramUpdate, text: String) {
        sendTelegramMessage(update.chatId, text, update.messageId)
    }

    private fun confirmationText(scope: String, configuration: LanguageConfiguration): String =
        "Language updated successfully.\n\n" +
            "Scope: $scope\n" +
            "Translation: ${configuration.describe()}\n\n" +
            "This will affect future translations only."

    private fun LanguageConfiguration.describe(): String = "${source.displayName} \u2192 ${target.displayName}"

    /** "Not configured" for the DEFAULT sentinel (Phase 9.1's "nothing set for this scope" signal), the real pair otherwise. */
    private fun LanguageConfiguration.describeOrUnset(): String =
        if (this == LanguageConfiguration.DEFAULT) "Not configured" else describe()

    private companion object {
        val HELP_TEXT = """
            Language Commands

            Show current language
            /language

            Set your language
            /language set user ar ru

            Set current group language
            /language set chat ar ru

            Set default language
            /language set global ar ru

            Examples:
            Arabic → Russian
            /language set user ar ru

            Russian → Arabic
            /language set user ru ar
        """.trimIndent()
    }
}
