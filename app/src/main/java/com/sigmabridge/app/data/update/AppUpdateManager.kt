package com.sigmabridge.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.sigmabridge.app.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String
)

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String
)

@Singleton
class AppUpdateManager @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val latestReleaseUrl =
        "https://api.github.com/repos/EngMohamedAshraf1/sigma-bridge-android1/releases/latest"

    fun checkForUpdate(): Result<AppUpdateInfo?> = runCatching {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sigma-Bridge-Android/${BuildConfig.VERSION_NAME}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Update check failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val release = json.decodeFromString<GitHubRelease>(body)

            if (release.draft || release.prerelease) return@use null

            val latestVersion = parseVersion(release.tagName) ?: return@use null
            val currentVersion = parseVersion(BuildConfig.VERSION_NAME) ?: return@use null
            if (latestVersion <= currentVersion) return@use null

            val asset = release.assets.firstOrNull { it.name == APK_ASSET_NAME }
                ?: return@use null

            AppUpdateInfo(
                versionName = latestVersion.joinToString("."),
                tagName = release.tagName,
                releaseNotes = release.body.trim(),
                downloadUrl = asset.browserDownloadUrl
            )
        }
    }

    fun downloadAndInstall(context: Context, update: AppUpdateInfo): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
            return@runCatching
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, APK_FILE_NAME)

        val request = Request.Builder()
            .url(update.downloadUrl)
            .header("User-Agent", "Sigma-Bridge-Android/${BuildConfig.VERSION_NAME}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("APK download failed: HTTP ${response.code}")
            val body = response.body ?: error("APK download returned an empty body.")
            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun parseVersion(value: String): List<Int>? {
        val match = VERSION_REGEX.find(value) ?: return null
        return match.value.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size in 2..3 }
    }

    private companion object {
        const val APK_ASSET_NAME = "sigma-bridge.apk"
        const val APK_FILE_NAME = "sigma-bridge-update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        val VERSION_REGEX = Regex("\\d+(?:\\.\\d+){1,2}")
    }
}
