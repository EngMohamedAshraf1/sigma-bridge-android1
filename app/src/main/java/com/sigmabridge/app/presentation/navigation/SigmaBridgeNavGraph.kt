package com.sigmabridge.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sigmabridge.app.presentation.bridge_control.BridgeControlScreen
import com.sigmabridge.app.presentation.chat.ChatScreen
import com.sigmabridge.app.presentation.gemini_test.GeminiTestScreen
import com.sigmabridge.app.presentation.home.HomeScreen
import com.sigmabridge.app.presentation.settings.SettingsScreen

@Composable
fun SigmaBridgeNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = SigmaBridgeDestination.Home.route) {
        composable(SigmaBridgeDestination.Home.route) {
            HomeScreen(
                onSettingsClick = { navController.navigate(SigmaBridgeDestination.Settings.route) },
                onGeminiTestClick = { navController.navigate(SigmaBridgeDestination.GeminiTest.route) },
                onBridgeControlClick = { navController.navigate(SigmaBridgeDestination.BridgeControl.route) },
                onChatClick = { navController.navigate(SigmaBridgeDestination.PrivateChat.route) }
            )
        }
        composable(SigmaBridgeDestination.Settings.route) {
            SettingsScreen()
        }
        composable(SigmaBridgeDestination.PrivateChat.route) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable(SigmaBridgeDestination.GeminiTest.route) {
            GeminiTestScreen()
        }
        composable(SigmaBridgeDestination.BridgeControl.route) {
            BridgeControlScreen()
        }
    }
}
