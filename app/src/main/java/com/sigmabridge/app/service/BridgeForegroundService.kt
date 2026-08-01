package com.sigmabridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sigmabridge.app.domain.pipeline.BridgeOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exactly what Phase 7 asked for and nothing more: start BridgeOrchestrator,
 * stay alive as a foreground service (the mandatory notification is the
 * only reason this class exists — Android requires it for any
 * long-running background work on API 26+), stop cleanly. No retry logic,
 * no networking, no notification content beyond a static "running" state —
 * all of that already lives in BridgeOrchestrator/TelegramRepository/
 * GeminiTranslationRepository and stays there.
 *
 * No BootReceiver, no WorkManager, no battery-optimization handling, no
 * automatic restart logic — deliberately deferred, per Phase 7's scope.
 * START_NOT_STICKY is intentional: if the system kills this process, it
 * does NOT come back on its own. That's Phase 8's problem, not this one's.
 */
@AndroidEntryPoint
class BridgeForegroundService : Service() {

    @Inject
    lateinit var bridgeOrchestrator: BridgeOrchestrator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                serviceScope.launch { bridgeOrchestrator.start() }
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    bridgeOrchestrator.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sigma Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sigma Bridge")
            .setContentText("Bridge is running")
            // Placeholder system icon — no custom monochrome status-bar asset exists yet;
            // swap for a real one before release (Phase 9 polish, not a Phase 7 concern).
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_START = "com.sigmabridge.app.action.START_BRIDGE"
        const val ACTION_STOP = "com.sigmabridge.app.action.STOP_BRIDGE"
        private const val CHANNEL_ID = "sigma_bridge_channel"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context): Intent =
            Intent(context, BridgeForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, BridgeForegroundService::class.java).setAction(ACTION_STOP)
    }
}
