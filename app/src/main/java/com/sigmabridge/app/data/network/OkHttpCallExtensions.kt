package com.sigmabridge.app.data.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp's synchronous execute() blocks the calling thread for the whole
 * request and cannot be interrupted by coroutine cancellation - only the
 * coroutine *wrapping* it gets marked cancelled, while the network call
 * itself keeps running underneath until it finishes on its own. That means
 * stopping the bridge while a request is in flight can hang for up to the
 * client's full read timeout instead of returning promptly.
 *
 * This bridges Call.enqueue() (which OkHttp can actually abort mid-flight)
 * to a suspend function: if the coroutine using this is cancelled,
 * invokeOnCancellation cancels the real OkHttp Call immediately.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }
    })

    continuation.invokeOnCancellation {
        cancel()
    }
}
