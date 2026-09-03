package com.sigmabridge.app.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.sigmabridge.app.presentation.navigation.SigmaBridgeNavGraph
import com.sigmabridge.app.presentation.theme.SigmaBridgeTheme
import com.sigmabridge.app.service.ChatNotificationService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the Private Chat background worker as soon as the app process is
        // opened. This is independent of Telegram/Sigma Call and only observes
        // saved Private Chat conversations.
        ContextCompat.startForegroundService(
            this,
            Intent(this, ChatNotificationService::class.java).setAction(ChatNotificationService.ACTION_START)
        )

        val openPrivateChat = intent?.getBooleanExtra(EXTRA_OPEN_PRIVATE_CHAT, false) == true
        setContent {
            SigmaBridgeTheme {
                SigmaBridgeNavGraph(openPrivateChat = openPrivateChat)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
    }
}
