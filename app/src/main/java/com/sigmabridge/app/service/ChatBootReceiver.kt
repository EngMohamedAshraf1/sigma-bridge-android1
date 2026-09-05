package com.sigmabridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sigmabridge.app.data.chat.ChatAccountRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Restarts Private Chat background messaging after device boot for permanent accounts only. */
@AndroidEntryPoint
class ChatBootReceiver : BroadcastReceiver() {

    @Inject lateinit var chatAccountRepository: ChatAccountRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!chatAccountRepository.isAuthenticated()) return

        ContextCompat.startForegroundService(
            context,
            ChatNotificationService.startIntent(context)
        )
    }
}
