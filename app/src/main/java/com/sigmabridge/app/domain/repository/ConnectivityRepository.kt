package com.sigmabridge.app.domain.repository

import com.sigmabridge.app.domain.model.InternetHealth
import kotlinx.coroutines.flow.Flow

/** Real (not placeholder) network connectivity signal. */
interface ConnectivityRepository {
    val health: Flow<InternetHealth>
}
