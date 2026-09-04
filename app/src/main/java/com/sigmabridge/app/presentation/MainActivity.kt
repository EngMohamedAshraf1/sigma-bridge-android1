package com.sigmabridge.app.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sigmabridge.app.data.update.AppUpdateInfo
import com.sigmabridge.app.data.update.AppUpdateManager
import com.sigmabridge.app.presentation.navigation.SigmaBridgeNavGraph
import com.sigmabridge.app.presentation.theme.SigmaBridgeTheme
import com.sigmabridge.app.service.ChatNotificationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject
    lateinit var appUpdateManager: AppUpdateManager

    private var availableUpdate by mutableStateOf<AppUpdateInfo?>(null)
    private var updateError by mutableStateOf<String?>(null)
    private var lastUpdateCheckAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the Private Chat background worker as soon as the app process is
        // opened. This remains independent of the update system and Telegram.
        ContextCompat.startForegroundService(
            this,
            Intent(this, ChatNotificationService::class.java).setAction(ChatNotificationService.ACTION_START)
        )

        val openPrivateChat = intent?.getBooleanExtra(EXTRA_OPEN_PRIVATE_CHAT, false) == true
        setContent {
            SigmaBridgeTheme {
                SigmaBridgeNavGraph(openPrivateChat = openPrivateChat)

                availableUpdate?.let { update ->
                    AlertDialog(
                        onDismissRequest = { availableUpdate = null },
                        title = { Text("يتوفر تحديث جديد لـ Sigma Bridge") },
                        text = {
                            Text(
                                buildString {
                                    append("الإصدار الجديد: ${update.versionName}\n\n")
                                    if (update.releaseNotes.isNotBlank()) append(update.releaseNotes)
                                    else append("يتوفر إصدار أحدث من التطبيق.")
                                }
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                updateError = null
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val result = appUpdateManager.downloadAndInstall(this@MainActivity, update)
                                    if (result.isFailure) {
                                        val message = result.exceptionOrNull()?.message
                                            ?: "تعذر تنزيل التحديث."
                                        withContext(Dispatchers.Main) {
                                            updateError = message
                                        }
                                    }
                                }
                            }) {
                                Text("تحديث الآن")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { availableUpdate = null }) {
                                Text("لاحقًا")
                            }
                        }
                    )
                }

                updateError?.let { message ->
                    AlertDialog(
                        onDismissRequest = { updateError = null },
                        title = { Text("تعذر التحديث") },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { updateError = null }) {
                                Text("حسنًا")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheckAt = now

        lifecycleScope.launch(Dispatchers.IO) {
            val result = appUpdateManager.checkForUpdate()
            if (result.isSuccess) {
                val update = result.getOrNull()
                if (update != null) {
                    withContext(Dispatchers.Main) {
                        availableUpdate = update
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        private const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }
}
