package com.sigmabridge.app.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sigmabridge.app.presentation.navigation.SigmaBridgeNavGraph
import com.sigmabridge.app.presentation.theme.SigmaBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var supabase: SupabaseClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthDeepLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
        recreate()
    }

    private fun handleAuthDeepLink(intent: Intent?) {
        intent ?: return
        runCatching { supabase.handleDeeplinks(intent) }
    }

    private fun render() {
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
