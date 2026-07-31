package com.sigmabridge.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Deliberately empty beyond the Hilt annotation for Phase 1 — no service
 * bootstrapping, no WorkManager scheduling here. Those belong to Phase 7/8
 * once TelegramRepository has a real implementation to start.
 */
@HiltAndroidApp
class SigmaBridgeApplication : Application()
