package com.sigmabridge.app.data.assistant

import com.sigmabridge.app.data.network.await
import com.sigmabridge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class TavilySearchResult(
    val title: String,
    val url: String,
    val content: String,
    val publishedDate: String?
)

class TavilySearchException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Small direct REST client for Tavily Search. The API key is read from
 * encrypted app settings and is sent only in the Authorization header.
 */
@Singleton
class TavilySearchClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) {
    suspend fun search(query: String): List<TavilySearchResult> = withContext(Dispatchers.IO) {
        val apiKey = settingsRepository.tavilyApiKey.first()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            throw TavilySearchException(
                "مفتاح Tavily غير محفوظ. افتح إعدادات Sigma Bridge وأضف Tavily Search API Key."
            )
        }

        val cleanedQuery = QUERY_NOISE_REGEX
            .replace(query, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(MAX_QUERY_LENGTH)

        if (cleanedQuery.isBlank()) {
            throw TavilySearchException("اكتب سؤالًا واضحًا حتى يمكن البحث عنه.")
        }

        val payload = buildJsonObject {
            put("query", cleanedQuery)
            put("search_depth", "basic")
            put("topic", "general")
            put("max_results", MAX_RESULTS)
            put("include_answer", false)
            put("include_raw_content", false)
        }

        val request = Request.Builder()
            .url(SEARCH_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(request).apply {
            timeout().timeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        call.await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val root = json.parseToJsonElement(body).jsonObject
                    (root["detail"] ?: root["message"] ?: root["error"])
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty().take(180)
                val hint = if (detail.isBlank()) "" else ": $detail"
                throw TavilySearchException("Tavily Search failed with HTTP ${response.code}$hint", response.code)
            }

            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw TavilySearchException("تعذر قراءة استجابة Tavily Search.") }
            val results = (root["results"] as? JsonArray).orEmpty().mapNotNull { item ->
                val result = item as? JsonObject ?: return@mapNotNull null
                val url = result["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val title = result["title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: url
                val snippet = result["content"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_SNIPPET_LENGTH)
                val publishedDate = result["published_date"]?.jsonPrimitive?.contentOrNull
                TavilySearchResult(title, url, snippet, publishedDate)
            }.distinctBy { it.url }.take(MAX_RESULTS)

            if (results.isEmpty()) {
                throw TavilySearchException("لم يعثر Tavily على نتائج مناسبة لهذا السؤال.")
            }
            results
        }
    }

    private companion object {
        const val SEARCH_URL = "https://api.tavily.com/search"
        const val SEARCH_TIMEOUT_SECONDS = 20L
        const val MAX_RESULTS = 5
        const val MAX_QUERY_LENGTH = 700
        const val MAX_SNIPPET_LENGTH = 1400
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val WHITESPACE_REGEX = Regex("\\s+")
        val QUERY_NOISE_REGEX = Regex(
            "(?im)^\\s*(Message being replied to \\(text only\\):|Group member request:)\\s*"
        )
    }
}
