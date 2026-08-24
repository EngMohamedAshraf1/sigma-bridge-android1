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
    data object PrivateChat : SigmaBridgeDestination("private_chat")

    /** Internal-only: validates Gemini in isolation; not linked from any user-facing tile. */
    data object GeminiTest : SigmaBridgeDestination("gemini_test")

    /** Internal-only: manual Start/Stop for BridgeOrchestrator until Phase 7's Foreground Service replaces it. */
    data object BridgeControl : SigmaBridgeDestination("bridge_control")
}
