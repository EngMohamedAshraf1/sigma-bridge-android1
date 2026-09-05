package com.sigmabridge.app.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sigmabridge.app.presentation.navigation.SigmaBridgeNavGraph
import com.sigmabridge.app.presentation.theme.SigmaBridgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preferences = remember {
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            }
            val defaultDarkTheme = isSystemInDarkTheme()
            var darkTheme by remember {
                mutableStateOf(
                    if (preferences.contains(DARK_THEME_KEY)) {
                        preferences.getBoolean(DARK_THEME_KEY, defaultDarkTheme)
                    } else {
                        defaultDarkTheme
                    }
                )
            }

            SigmaBridgeTheme(darkTheme = darkTheme) {
                SigmaBridgeNavGraph(
                    openPrivateChat = intent?.getBooleanExtra(EXTRA_OPEN_PRIVATE_CHAT, false) == true,
                    darkTheme = darkTheme,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        preferences.edit().putBoolean(DARK_THEME_KEY, darkTheme).apply()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        private const val PREFERENCES_NAME = "sigma_bridge_preferences"
        private const val DARK_THEME_KEY = "dark_theme"
    }
}
