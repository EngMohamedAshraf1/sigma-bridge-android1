package com.sigmabridge.app.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sigmabridge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object UpdateManager {
    private val checker = GitHubUpdateChecker()
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()

    suspend fun checkNow(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(checking = true, errorMessage = null)
            runCatching {
                checker.check(currentVersion)
            }.onSuccess { result ->
                _state.value = UpdateUiState(
                    update = result.takeIf { it.updateAvailable },
                    checking = false
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    checking = false,
                    errorMessage = error.message
                )
            }.getOrThrow()
        }

    suspend fun checkOnLaunch(currentVersion: String = BuildConfig.VERSION_NAME) {
        runCatching { checkNow(currentVersion) }
    }

    fun downloadAndInstall(context: Context, update: UpdateCheckResult) {
        if (update.apkUrl.isBlank() || _state.value.downloading) return

        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            _state.value = _state.value.copy(
                errorMessage = "INSTALL_PERMISSION_REQUIRED"
            )
            return
        }

        _state.value = _state.value.copy(downloading = true, errorMessage = null)

        val fileName = update.apkFileName.ifBlank {
            "SigmaBridge-v${update.latestVersion}.apk"
        }
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Sigma Bridge v${update.latestVersion}")
            .setDescription("Downloading update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )

        val downloadManager = ContextCompat.getSystemService(
            appContext,
            DownloadManager::class.java
        ) ?: run {
            _state.value = _state.value.copy(
                downloading = false,
                errorMessage = "DOWNLOAD_MANAGER_UNAVAILABLE"
            )
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId <= 0L) return

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                val status = cursor.use {
                    if (it != null && it.moveToFirst()) {
                        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    } else {
                        DownloadManager.STATUS_FAILED
                    }
                }

                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    _state.value = _state.value.copy(
                        downloading = false,
                        errorMessage = "DOWNLOAD_FAILED"
                    )
                    safeUnregister(appContext, this)
                    return
                }

                val uri = downloadManager.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    _state.value = _state.value.copy(
                        downloading = false,
                        errorMessage = "DOWNLOAD_URI_UNAVAILABLE"
                    )
                    safeUnregister(appContext, this)
                    return
                }

                _state.value = _state.value.copy(downloading = false, installing = true)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    appContext.startActivity(installIntent)
                }.onFailure {
                    _state.value = _state.value.copy(
                        installing = false,
                        errorMessage = "INSTALL_FAILED"
                    )
                }
                safeUnregister(appContext, this)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        runCatching {
            downloadManager.enqueue(request)
        }.onFailure {
            safeUnregister(appContext, receiver)
            _state.value = _state.value.copy(
                downloading = false,
                errorMessage = "DOWNLOAD_FAILED"
            )
        }
    }

    private fun safeUnregister(context: Context, receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

data class UpdateUiState(
    val update: UpdateCheckResult? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val installing: Boolean = false,
    val errorMessage: String? = null
)
