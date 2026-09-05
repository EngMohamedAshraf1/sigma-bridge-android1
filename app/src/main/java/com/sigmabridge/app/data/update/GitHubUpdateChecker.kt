package com.sigmabridge.app.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class GitHubUpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Sigma-Bridge-Android")
            }

            try {
                if (connection.responseCode !in 200..299) {
                    error("GitHub returned HTTP ${connection.responseCode}")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = json.parseToJsonElement(body).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.content
                    ?: error("Latest release tag is missing")
                val name = root["name"]?.jsonPrimitive?.content.orEmpty()
                val url = root["html_url"]?.jsonPrimitive?.content.orEmpty()

                val latestVersion = normalizeVersion(tag)
                val installedVersion = normalizeVersion(currentVersion)

                UpdateCheckResult(
                    currentVersion = installedVersion,
                    latestVersion = latestVersion,
                    releaseName = name,
                    releaseUrl = url,
                    updateAvailable = compareVersions(latestVersion, installedVersion) > 0
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            throw UpdateCheckException(error.message ?: "Unable to check for updates", error)
        }
    }

    private fun normalizeVersion(value: String): String =
        value.trim().removePrefix("v").substringBefore('-').ifBlank { "0.0.0" }

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split('.').map { it.toIntOrNull() ?: 0 }
        val b = second.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/EngMohamedAshraf1/sigma-bridge-android1/releases/latest"
    }
}

data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String,
    val releaseUrl: String,
    val updateAvailable: Boolean
)

class UpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)
