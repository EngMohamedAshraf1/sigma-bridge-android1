package com.sigmabridge.app.domain.repository

import kotlinx.coroutines.flow.Flow

/** Real (not placeholder) network connectivity signal. */
interface ConnectivityRepository {
    val isConnected: Flow<Boolean>
}
