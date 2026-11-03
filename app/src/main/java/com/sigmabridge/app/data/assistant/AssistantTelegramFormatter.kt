package com.sigmabridge.app.data.assistant

/** Telegram-safe output formatting for the isolated text assistant. */
object AssistantTelegramFormatter {
    private val citationRegex = Regex("(?<!\\S)\\[(\\d{1,2})](?!\\s)")
    private val sourceHeadingRegex = Regex(
        "(?i)^\\s*(?:المصادر|مصادر(?: البحث)?|sources|references)\\s*(?:\\([^\\n]*\\))?\\s*[:：].*$"
    )
    private val rawUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val headingRegex = Regex("^\\s*#{1,3}\\s+(.+)$")
    private val bulletRegex = Regex("^\\s*[-*]\\s+(.+)$")
    private val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
    private val italicRegex = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
    private val codeRegex = Regex("\\x60([^\\x60]+)\\x60")

    fun format(response: AssistantResponse): String {
        val cleanText = removeGeneratedSourceSection(response.answer).replace(rawUrlRegex, "").trim()
        val citedIndexes = citationRegex.findAll(cleanText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..response.sources.size }
            .distinct()
            .toList()
        val body = formatBody(cleanText)
        if (citedIndexes.isEmpty()) return body

        val links = citedIndexes.map { index ->
            val source = response.sources[index - 1]
            val label = compactSourceLabel(source)
            """<a href="${escapeAttribute(source.url)}">[$index] ${escapeText(label)}</a>"""
        }.joinToString(" · ")
        return "$body\n\n<b>المصادر:</b> $links"
    }

    private fun removeGeneratedSourceSection(text: String): String {
        val lines = text.lines()
        val index = lines.indexOfFirst { sourceHeadingRegex.matches(it) }
        return if (index >= 0) lines.take(index).joinToString("\n").trimEnd() else text
    }

    private fun formatBody(text: String): String = text.lines().joinToString("\n") { raw ->
        val line = raw.trimEnd()
        val heading = headingRegex.matchEntire(line)
        val bullet = bulletRegex.matchEntire(line)
        when {
            heading != null -> "<b>${formatInline(heading.groupValues[1])}</b>"
            bullet != null -> "• ${formatInline(bullet.groupValues[1])}"
            else -> formatInline(line)
        }
    }.trim()

    private fun formatInline(value: String): String {
        var safe = escapeText(value)
        safe = codeRegex.replace(safe) { "<code>${it.groupValues[1]}</code>" }
        safe = boldRegex.replace(safe) { "<b>${it.groupValues[1]}</b>" }
        safe = italicRegex.replace(safe) { "<i>${it.groupValues[1]}</i>" }
        return safe
    }

    private fun compactSourceLabel(source: TavilySearchResult): String {
        val title = source.title.replace(Regex("\\s+"), " ").trim()
        val value = if (title.isBlank() || title == source.url) {
            runCatching { java.net.URI(source.url).host?.removePrefix("www.") }
                .getOrNull().orEmpty().ifBlank { "المصدر" }
        } else title
        return if (value.length > MAX_SOURCE_LABEL_CHARS) {
            value.take(MAX_SOURCE_LABEL_CHARS - 1).trimEnd() + "…"
        } else value
    }

    private fun escapeText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttribute(value: String): String = escapeText(value)
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private const val MAX_SOURCE_LABEL_CHARS = 34
}
