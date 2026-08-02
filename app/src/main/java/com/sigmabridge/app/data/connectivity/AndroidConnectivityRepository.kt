package com.sigmabridge.app.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.sigmabridge.app.domain.model.InternetHealth
import com.sigmabridge.app.domain.repository.ConnectivityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ConnectivityManager.NetworkCallback is inherently callback-based on the
 * Android side — there's no suspend/Flow equivalent in the platform API.
 * This class is the one place that callback is allowed to exist; it's
 * wrapped in callbackFlow immediately so every caller above this class
 * (use cases, ViewModels) only ever sees a cold Flow<InternetHealth>,
 * consistent with "coroutines + Flow, not callbacks" as the app's boundary.
 */
@Singleton
class AndroidConnectivityRepository @Inject constructor(
    @ApplicationContext context: Context
) : ConnectivityRepository {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val health: Flow<InternetHealth> = callbackFlow {
        fun currentHealth(): InternetHealth {
            val network = connectivityManager.activeNetwork ?: return InternetHealth.OFFLINE
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return InternetHealth.OFFLINE
            val hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return when {
                hasInternetCapability && isValidated -> InternetHealth.CONNECTED
                hasInternetCapability -> InternetHealth.LIMITED
                else -> InternetHealth.OFFLINE
            }
        }

        trySend(currentHealth())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentHealth())
            }

            override fun onLost(network: Network) {
                trySend(currentHealth())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(currentHealth())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
