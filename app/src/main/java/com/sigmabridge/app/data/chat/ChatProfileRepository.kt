package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatProfileRepository @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    suspend fun publish(profile: ChatProfile): Result<Unit> = runCatching {
        val body = json.encodeToString(ChatProfile.serializer(), profile)
            .toRequestBody(JSON_MEDIA_TYPE)
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$BASE_URL/${profileTopic(profile.username)}")
                    .post(body)
                    .header("Cache", "yes")
                    .build()
            ).execute().use { response ->
                check(response.isSuccessful) { "Profile directory returned HTTP ${response.code}" }
            }
        }
    }

    suspend fun find(username: String): Result<ChatProfile?> = runCatching {
        val normalized = normalizeUsername(username)
        if (normalized.isBlank()) return@runCatching null
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$BASE_URL/${profileTopic(normalized)}/json?poll=1&since=24h")
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                body.byteStream().bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val envelope = runCatching {
                            json.decodeFromString(NtfyEnvelope.serializer(), line)
                        }.getOrNull() ?: return@mapNotNull null
                        if (envelope.event != "message" || envelope.message.isNullOrBlank()) return@mapNotNull null
                        runCatching { json.decodeFromString(ChatProfile.serializer(), envelope.message) }
                            .getOrNull()
                    }.lastOrNull()
                }
            }
        }
    }

    companion object {
        fun normalizeUsername(value: String): String = value.trim()
            .removePrefix("@")
            .lowercase(Locale.US)

        fun validateUsername(value: String): Boolean =
            Regex("^[a-z0-9_]{5,32}$").matches(normalizeUsername(value))

        private fun profileTopic(username: String): String = "sigma-bridge-user-${normalizeUsername(username)}"
        private const val BASE_URL = "https://ntfy.sh"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @kotlinx.serialization.Serializable
    private data class NtfyEnvelope(
        val event: String,
        val message: String? = null
    )
}
