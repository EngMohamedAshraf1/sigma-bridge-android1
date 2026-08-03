package com.sigmabridge.app.domain.model

data class StoredCredentials(
    val botToken: String?,
    val geminiApiKeys: List<String>
)
