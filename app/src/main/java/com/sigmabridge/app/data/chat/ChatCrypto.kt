package com.sigmabridge.app.data.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encrypts chat message text before it reaches the public ntfy relay.
 * The shared 256-bit chat secret is wrapped by an Android Keystore key and
 * never stored in plaintext in SharedPreferences.
 */
class ChatCrypto {

    companion object {
        const val PAIRING_PREFIX = "SB1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val WRAP_ALIAS = "SigmaBridgeChatWrapKey"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val SECRET_BYTES = 32
        private const val WRAPPED_PREFIX = "wrapped_secret_v1"

        @OptIn(ExperimentalEncodingApi::class)
        fun generatePairingCode(roomCode: String): String {
            val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
            val secretText = Base64.UrlSafe.encode(secret).trimEnd('=')
            return "$PAIRING_PREFIX.${roomCode.trim()}.$secretText"
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun installPairingCode(pairingCode: String): String {
        val parts = pairingCode.trim().split('.')
        require(parts.size == 3 && parts[0] == PAIRING_PREFIX) { "Invalid pairing code." }
        val room = parts[1].trim()
        require(room.isNotBlank()) { "Pairing code has no room." }
        val secret = Base64.UrlSafe.decode(padBase64(parts[2]))
        require(secret.size == SECRET_BYTES) { "Invalid pairing secret." }
        return saveSecret(secret)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun secretFromPairingCode(pairingCode: String): ByteArray {
        val parts = pairingCode.trim().split('.')
        require(parts.size == 3 && parts[0] == PAIRING_PREFIX) { "Invalid pairing code." }
        val secret = Base64.UrlSafe.decode(padBase64(parts[2]))
        require(secret.size == SECRET_BYTES) { "Invalid pairing secret." }
        return secret
    }

    fun saveSecret(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "Invalid pairing secret." }
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(secret)
        return encodeWrapped(iv, ciphertext)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun loadSecret(wrapped: String): ByteArray {
        val parts = wrapped.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == WRAPPED_PREFIX) { "Invalid stored chat key." }
        val iv = Base64.UrlSafe.decode(padBase64(parts[1]))
        val ciphertext = Base64.UrlSafe.decode(padBase64(parts[2]))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encrypt(text: String, secret: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(secret), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(1)
            .put(iv)
            .put(ciphertext)
            .array()
        return "sb1:" + Base64.UrlSafe.encode(payload).trimEnd('=')
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(value: String, secret: ByteArray): String {
        require(value.startsWith("sb1:")) { "Encrypted chat message required." }
        val payload = Base64.UrlSafe.decode(padBase64(value.removePrefix("sb1:")))
        require(payload.isNotEmpty() && payload[0].toInt() == 1) { "Unsupported encrypted message." }
        require(payload.size > 1 + IV_BYTES) { "Invalid encrypted message." }
        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(secret), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeWrapped(iv: ByteArray, ciphertext: ByteArray): String =
        "$WRAPPED_PREFIX:${Base64.UrlSafe.encode(iv).trimEnd('=')}:${Base64.UrlSafe.encode(ciphertext).trimEnd('=')}"

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(WRAP_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun secretKey(secret: ByteArray): SecretKey =
        javax.crypto.spec.SecretKeySpec(secret, "AES")

    @OptIn(ExperimentalEncodingApi::class)
    private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
}
