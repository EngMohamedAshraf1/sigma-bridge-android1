package com.sigmabridge.app.data.chat

import android.util.Base64
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight chat encryption for the user-ID based design.
 * The current protocol uses the deterministic conversation key derived from
 * both high-entropy SB IDs and a fresh random IV for every message.
 */
@Singleton
class ChatCrypto @Inject constructor(
    private val identity: ChatIdentity
) {
    private companion object {
        const val PREFIX = "sb2:"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val RANDOM = SecureRandom()

        fun encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun decode(value: String): ByteArray =
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun encrypt(text: String): String {
        val iv = ByteArray(IV_BYTES).also(RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(identity.conversationKey(), "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(VERSION)
            .put(iv)
            .put(ciphertext)
            .array()
        return PREFIX + encode(payload)
    }

    /** Returns the Base64URL representation of the IV stored in the encrypted payload. */
    fun nonceFromEncrypted(value: String): String {
        val payload = decodePayload(value)
        return encode(payload.copyOfRange(1, 1 + IV_BYTES))
    }

    fun decrypt(value: String): String {
        val payload = decodePayload(value)
        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(identity.conversationKey(), "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun decodePayload(value: String): ByteArray {
        require(value.startsWith(PREFIX)) { "Encrypted chat message required." }
        val payload = decode(value.removePrefix(PREFIX))
        require(payload.size > 1 + IV_BYTES && payload[0] == VERSION) {
            "Invalid encrypted message."
        }
        return payload
    }
}
