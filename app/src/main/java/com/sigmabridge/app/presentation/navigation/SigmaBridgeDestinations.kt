package com.sigmabridge.app.presentation.navigation

/**
 * Every screen Sigma Bridge can navigate to. Home is the platform hub;
 * each translation mode gets its own route so a new feature (OCR, Photos,
 * PDF) is "add a destination + add a tile on Home", never a Home redesign.
 */
sealed class SigmaBridgeDestination(val route: String) {
    data object Home : SigmaBridgeDestination("home")
    data object VoiceBridge : SigmaBridgeDestination("voice_bridge")
    data object Ocr : SigmaBridgeDestination("ocr")
    data object Photos : SigmaBridgeDestination("photos")
    data object Pdf : SigmaBridgeDestination("pdf")
    data object Settings : SigmaBridgeDestination("settings")
}
