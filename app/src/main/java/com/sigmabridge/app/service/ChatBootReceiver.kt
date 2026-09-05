package com.sigmabridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

/** Restarts Private Chat background messaging after device boot. */
@AndroidEntryPoint
class ChatBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // Private Chat can receive a first message before any local conversation
        // exists, so boot handling must not depend on a saved partner ID.
        ContextCompat.startForegroundService(
            context,
            ChatNotificationService.startIntent(context)
        )
    }
}
