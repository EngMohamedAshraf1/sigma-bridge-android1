package com.sigmabridge.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sigmabridge.app.presentation.home.HomeScreen

/**
 * Only Home + a placeholder for the (not-yet-built) Voice Bridge screen
 * exist in Phase 1. Ocr/Photos/Pdf/Settings routes are declared in
 * [SigmaBridgeDestination] but intentionally have no composable() entry
 * here yet — Home only ever links to routes that exist, and disabled tiles
 * simply don't navigate. Wiring them up is later-phase work, not Phase 1.
 */
@Composable
fun SigmaBridgeNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = SigmaBridgeDestination.Home.route) {
        composable(SigmaBridgeDestination.Home.route) {
            HomeScreen()
        }
    }
}
