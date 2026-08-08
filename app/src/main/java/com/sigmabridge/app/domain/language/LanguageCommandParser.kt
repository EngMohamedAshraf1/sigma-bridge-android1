package com.sigmabridge.app.domain.language

import javax.inject.Inject

/**
 * Requirement 7 ("Telegram command parsing must stay separated from
 * language storage"): this class never touches LanguagePreferencesRepository,
 * never sends a Telegram message, never checks a permission. It only turns
 * raw message text into a LanguageCommand. LanguageCommandHandler is the
 * only thing that calls both this and the storage/permission layers.
 */
class LanguageCommandParser @Inject constructor() {

    /** Null means the text isn't a /language command at all (a different message, including voice). */
    fun parse(text: String?): LanguageCommand? {
        val trimmed = text?.trim() ?: return null
        val match = COMMAND_REGEX.find(trimmed) ?: return null

        val argsText = match.groupValues[1].trim()
        val args = if (argsText.isEmpty()) emptyList() else argsText.split(WHITESPACE_REGEX)

        return when {
            args.isEmpty() -> LanguageCommand.ShowAll
            args.size == 1 && args[0].equals("global", ignoreCase = true) -> LanguageCommand.ShowGlobal
            args.size == 1 && args[0].equals("user", ignoreCase = true) -> LanguageCommand.ShowUser
            args.size == 1 && args[0].equals("chat", ignoreCase = true) -> LanguageCommand.ShowChat
            args.size == 4 && args[0].equals("set", ignoreCase = true) && args[1].equals("global", ignoreCase = true) ->
                LanguageCommand.SetGlobal(sourceCode = args[2], targetCode = args[3])
            args.size == 4 && args[0].equals("set", ignoreCase = true) && args[1].equals("user", ignoreCase = true) ->
                LanguageCommand.SetUser(sourceCode = args[2], targetCode = args[3])
            args.size == 4 && args[0].equals("set", ignoreCase = true) && args[1].equals("chat", ignoreCase = true) ->
                LanguageCommand.SetChat(sourceCode = args[2], targetCode = args[3])
            else -> LanguageCommand.Unrecognized
        }
    }

    private companion object {
        // Matches "/language", "/language@BotName", with or without trailing arguments.
        // Telegram appends "@BotName" to commands in group chats when the bot has a username
        // collision risk, so it must be stripped here rather than assumed absent.
        val COMMAND_REGEX = Regex("^/language(?:@\\S+)?(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
