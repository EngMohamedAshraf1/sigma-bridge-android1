package com.sigmabridge.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sigmabridge.app.presentation.bridge_control.BridgeControlScreen
import com.sigmabridge.app.presentation.gemini_test.GeminiTestScreen
import com.sigmabridge.app.presentation.home.HomeScreen
import com.sigmabridge.app.presentation.settings.SettingsScreen

/**
 * Home + Settings + the two internal debug screens exist as of Phase 6.
 * Ocr/Photos/Pdf routes are still declared in [SigmaBridgeDestination] with
 * no composable() entry — same "only wire a route once the screen behind
 * it exists" rule as before.
 */
@Composable
fun SigmaBridgeNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = SigmaBridgeDestination.Home.route) {
        composable(SigmaBridgeDestination.Home.route) {
            HomeScreen(
                onSettingsClick = { navController.navigate(SigmaBridgeDestination.Settings.route) },
                onGeminiTestClick = { navController.navigate(SigmaBridgeDestination.GeminiTest.route) },
                onBridgeControlClick = { navController.navigate(SigmaBridgeDestination.BridgeControl.route) }
            )
        }
        composable(SigmaBridgeDestination.Settings.route) {
            SettingsScreen()
        }
        composable(SigmaBridgeDestination.GeminiTest.route) {
            GeminiTestScreen()
        }
        composable(SigmaBridgeDestination.BridgeControl.route) {
            BridgeControlScreen()
        }
    }
}
