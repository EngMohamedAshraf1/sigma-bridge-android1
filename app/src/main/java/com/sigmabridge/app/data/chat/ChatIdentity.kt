package com.sigmabridge.app.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.security.MessageDigest
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple persistent chat identity.
 * The user-facing ID is intentionally high-entropy and acts as a capability:
 * only people who know both IDs can address the same private conversation.
 */
@Singleton
class ChatIdentity @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val myId: String by lazy {
        preferences.getString(KEY_MY_ID, null) ?: generateAndStoreId()
    }

    var partnerId: String
        get() = preferences.getString(KEY_PARTNER_ID, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_PARTNER_ID, value.trim()).apply() }

    fun conversationTopic(): String {
        val partner = partnerId
        require(partner.isNotBlank()) { "Enter the partner ID first." }
        return conversationTopicFor(partner)
    }

    fun conversationTopicFor(partnerId: String): String {
        val partner = partnerId.trim()
        require(partner.isNotBlank()) { "Partner ID must not be blank." }
        val combined = listOf(myId, partner).sorted().joinToString("|")
        return "sigma-bridge-${sha256(combined).take(32)}"
    }

    /** Deterministic 256-bit conversation key derived from both high-entropy IDs. */
    fun conversationKey(): ByteArray {
        val partner = partnerId
        require(partner.isNotBlank()) { "Enter the partner ID first." }
        return conversationKeyFor(partner)
    }

    fun conversationKeyFor(partnerId: String): ByteArray {
        val partner = partnerId.trim()
        require(partner.isNotBlank()) { "Partner ID must not be blank." }
        val combined = listOf(myId, partner).sorted().joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())
    }

    fun securityFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(conversationKey())
        val value = ByteBuffer.wrap(digest.copyOfRange(0, 4)).int.toUInt().toString()
        return value.padStart(10, '0')
    }

    private fun generateAndStoreId(): String {
        val bytes = ByteArray(18).also(SecureRandom()::nextBytes)
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val result = buildString {
            for (b in bytes) append(alphabet[(b.toInt() and 0xFF) % alphabet.length])
        }
        val formatted = "SB-" + result.chunked(6).joinToString("-")
        preferences.edit().putString(KEY_MY_ID, formatted).apply()
        return formatted
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_identity"
        const val KEY_MY_ID = "my_id"
        const val KEY_PARTNER_ID = "partner_id"
    }
}
