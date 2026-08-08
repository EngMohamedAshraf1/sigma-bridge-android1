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
            is LanguageCommand.ShowGlobal -> reply(update, "Global: ${getGlobalLanguage().describe()}")
            is LanguageCommand.ShowUser -> reply(update, "Your language: ${getUserLanguage(senderId).describe()}")
            is LanguageCommand.ShowChat -> reply(update, "Chat language: ${getChatLanguage(update.chatId).describe()}")
            is LanguageCommand.SetGlobal -> handleSetGlobal(update, senderId, command)
            is LanguageCommand.SetChat -> handleSetChat(update, senderId, command)
            is LanguageCommand.SetUser -> handleSetUser(update, senderId, command)
            is LanguageCommand.Unrecognized -> reply(update, USAGE_TEXT)
        }
    }

    private suspend fun handleShowAll(update: TelegramUpdate, senderId: Long) {
        val user = getUserLanguage(senderId)
        val chat = getChatLanguage(update.chatId)
        val global = getGlobalLanguage()
        val effective = languageResolver.resolve(chatId = update.chatId, userId = senderId)
        reply(
            update,
            "User: ${user.describe()}\n" +
                "Chat: ${chat.describe()}\n" +
                "Global: ${global.describe()}\n" +
                "Effective: ${effective.describe()}"
        )
    }

    private suspend fun handleSetGlobal(update: TelegramUpdate, senderId: Long, command: LanguageCommand.SetGlobal) {
        if (!permissionChecker.authorizeGlobalChange(senderId)) {
            reply(update, "Only the bot owner can change the global language.")
            return
        }
        val configuration = parseConfiguration(update, command.sourceCode, command.targetCode) ?: return
        setGlobalLanguage(configuration)
        reply(update, "Global language set to ${configuration.describe()}")
    }

    private suspend fun handleSetChat(update: TelegramUpdate, senderId: Long, command: LanguageCommand.SetChat) {
        if (!permissionChecker.authorizeChatChange(update.chatId, senderId)) {
            reply(update, "Only group administrators can change the chat language.")
            return
        }
        val configuration = parseConfiguration(update, command.sourceCode, command.targetCode) ?: return
        setChatLanguage(update.chatId, configuration)
        reply(update, "Chat language set to ${configuration.describe()}")
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
        reply(update, "Your language set to ${configuration.describe()}")
    }

    private suspend fun parseConfiguration(update: TelegramUpdate, sourceCode: String, targetCode: String): LanguageConfiguration? {
        val source = Language.SUPPORTED.firstOrNull { it.code.equals(sourceCode, ignoreCase = true) }
        val target = Language.SUPPORTED.firstOrNull { it.code.equals(targetCode, ignoreCase = true) }
        if (source == null || target == null) {
            val supportedCodes = Language.SUPPORTED.joinToString(", ") { it.code }
            reply(update, "Unsupported language code. Supported: $supportedCodes")
            return null
        }
        return LanguageConfiguration(source = source, target = target)
    }

    private suspend fun reply(update: TelegramUpdate, text: String) {
        sendTelegramMessage(update.chatId, text, update.messageId)
    }

    private fun LanguageConfiguration.describe(): String = "${source.displayName} \u2192 ${target.displayName}"

    private companion object {
        val USAGE_TEXT = """
            Usage:
            /language - show current configuration
            /language global|user|chat - show one scope
            /language set global|user|chat <source> <target>
            Example: /language set user ru ar
        """.trimIndent()
    }
}
