package com.sigmabridge.app.domain.language

import javax.inject.Inject

/**
 * Same separation-of-concerns rule as LanguageCommandParser: this class
 * never touches LanguagePreferencesRepository, never sends a Telegram
 * message, never checks a permission — it only turns a raw callback_data
 * string into a LanguageCallback. LanguageCallbackHandler is the only
 * thing that calls both this and the storage/permission/Telegram layers.
 *
 * callback_data format (Telegram caps this at 64 bytes, so it stays terse):
 *   lang:menu:<scope>                       - e.g. "lang:menu:user"
 *   lang:src:<scope>:<sourceCode>           - e.g. "lang:src:user:ar"
 *   lang:tgt:<scope>:<sourceCode>:<target>  - e.g. "lang:tgt:user:ar:ru"
 */
class LanguageCallbackParser @Inject constructor() {

    fun parse(data: String?): LanguageCallback? {
        if (data == null) return null
        val parts = data.split(SEPARATOR)
        if (parts.size < 3 || parts[0] != PREFIX) return null

        return when (parts[1]) {
            "menu" -> parts.getOrNull(2)
                ?.let { LanguageScope.fromKey(it) }
                ?.let { LanguageCallback.SelectScope(it) }

            "src" -> if (parts.size == 4) {
                LanguageScope.fromKey(parts[2])?.let { scope ->
                    LanguageCallback.SelectSource(scope = scope, sourceCode = parts[3])
                }
            } else null

            "tgt" -> if (parts.size == 5) {
                LanguageScope.fromKey(parts[2])?.let { scope ->
                    LanguageCallback.SelectTarget(scope = scope, sourceCode = parts[3], targetCode = parts[4])
                }
            } else null

            else -> null
        }
    }

    companion object {
        private const val PREFIX = "lang"
        private const val SEPARATOR = ":"

        fun menuData(scope: LanguageScope): String = "$PREFIX:menu:${scope.callbackKey}"
        fun sourceData(scope: LanguageScope, sourceCode: String): String = "$PREFIX:src:${scope.callbackKey}:$sourceCode"
        fun targetData(scope: LanguageScope, sourceCode: String, targetCode: String): String =
            "$PREFIX:tgt:${scope.callbackKey}:$sourceCode:$targetCode"
    }
}
