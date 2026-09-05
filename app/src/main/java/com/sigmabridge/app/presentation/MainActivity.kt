package com.sigmabridge.app.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sigmabridge.app.presentation.navigation.SigmaBridgeNavGraph
import com.sigmabridge.app.presentation.theme.SigmaBridgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
