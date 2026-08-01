package com.sigmabridge.app.data.telegram

import com.sigmabridge.app.data.network.await
import com.sigmabridge.app.data.telegram.dto.TelegramGetFileResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

/**
 * Two Telegram Bot API calls, same two steps as downloader.py's
 * bot.get_file() + download_to_drive(): resolve file_id -> file_path, then
 * fetch the bytes from the file-serving host. Uses Call.await() (cancellable),
 * same posture as TelegramApiClient — no separate SDK.
 */
class TelegramFileApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    suspend fun getFilePath(botToken: String, fileId: String): String = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$botToken/getFile".toHttpUrl().newBuilder()
            .addQueryParameter("file_id", fileId)
            .build()

        httpClient.newCall(Request.Builder().url(url).build()).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TelegramApiException("getFile failed: HTTP ${response.code} — $body")
            }
            val parsed = json.decodeFromString(TelegramGetFileResponseDto.serializer(), body)
            if (!parsed.ok) throw TelegramApiException("getFile returned ok=false")
            parsed.result?.filePath ?: throw TelegramApiException("getFile returned no file_path")
        }
    }

    /** Streams the file directly to [destinationPath] — never buffers the whole voice note in memory. */
    suspend fun downloadFile(botToken: String, filePath: String, destinationPath: String) =
        withContext(Dispatchers.IO) {
            val url = "https://api.telegram.org/file/bot$botToken/$filePath"

            httpClient.newCall(Request.Builder().url(url).build()).await().use { response ->
                if (!response.isSuccessful) {
                    throw TelegramApiException("File download failed: HTTP ${response.code}")
                }
                val responseBody = response.body ?: throw TelegramApiException("Empty file download body")
                File(destinationPath).outputStream().use { output ->
                    responseBody.byteStream().copyTo(output)
                }
            }
        }
}
