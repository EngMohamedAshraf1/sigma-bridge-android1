package com.sigmabridge.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sigmabridge.app.presentation.home.HomeScreen
import com.sigmabridge.app.presentation.settings.SettingsScreen

/**
 * Home + Settings exist in Phase 2. Ocr/Photos/Pdf routes are declared in
 * [SigmaBridgeDestination] but still have no composable() entry — same
 * reasoning as Phase 1: only wire a route once the screen behind it exists.
 */
@Composable
fun SigmaBridgeNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = SigmaBridgeDestination.Home.route) {
        composable(SigmaBridgeDestination.Home.route) {
            HomeScreen(
                onSettingsClick = { navController.navigate(SigmaBridgeDestination.Settings.route) }
            )
        }
        composable(SigmaBridgeDestination.Settings.route) {
            SettingsScreen()
        }
    }
}
