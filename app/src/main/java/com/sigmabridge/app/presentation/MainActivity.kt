package com.sigmabridge.app.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.align
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sigmabridge.app.BuildConfig
import com.sigmabridge.app.data.update.UpdateManager
import com.sigmabridge.app.presentation.navigation.SigmaBridgeNavGraph
import com.sigmabridge.app.presentation.theme.SigmaBridgeTheme
import com.sigmabridge.app.presentation.update.UpdateBanner
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
            val updateState by UpdateManager.state.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                UpdateManager.checkOnLaunch(BuildConfig.VERSION_NAME)
            }

            SigmaBridgeTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SigmaBridgeNavGraph(
                        modifier = Modifier.fillMaxSize(),
                        openPrivateChat = intent?.getBooleanExtra(EXTRA_OPEN_PRIVATE_CHAT, false) == true,
                        darkTheme = darkTheme,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            preferences.edit().putBoolean(DARK_THEME_KEY, darkTheme).apply()
                        }
                    )

                    updateState.update?.let { update ->
                        UpdateBanner(
                            update = update,
                            downloading = updateState.downloading,
                            installing = updateState.installing,
                            onUpdateClick = {
                                UpdateManager.downloadAndInstall(context, update)
                            },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        private const val PREFERENCES_NAME = "sigma_bridge_preferences"
        private const val DARK_THEME_KEY = "dark_theme"
    }
}
