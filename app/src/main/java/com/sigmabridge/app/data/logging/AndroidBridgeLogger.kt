package com.sigmabridge.app.data.logging

import android.util.Log
import com.sigmabridge.app.domain.logging.BridgeLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidBridgeLogger @Inject constructor() : BridgeLogger {
    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
