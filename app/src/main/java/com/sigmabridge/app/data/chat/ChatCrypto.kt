package com.sigmabridge.app.data.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat encryption and one-time pairing material.
 *
 * A random 256-bit conversation secret is shared by the two paired devices.
 * The secret is wrapped at rest by an AES-GCM key held in Android Keystore,
 * while message content is encrypted with a fresh random IV per message.
 * Pairing is not considered verified until both users compare the displayed
 * security code out-of-band and explicitly confirm it on their devices.
 */
@Singleton
class ChatCrypto @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        const val PAIRING_PREFIX = "SB1"
        private const val PREFS_NAME = "sigma_bridge_chat_security"
        private const val KEY_WRAPPED_SECRET = "wrapped_secret"
        private const val KEY_VERIFIED = "pairing_verified"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val WRAP_ALIAS = "SigmaBridgeChatWrapKey"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val SECRET_BYTES = 32
        private const val MESSAGE_VERSION = 1
        private const val SECURITY_CODE_MODULUS = 1_000_000L
        private const val WRAPPED_SECRET_PREFIX = "wrapped_secret_v1"

        private fun encode(value: ByteArray): String =
            Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun decode(value: String): ByteArray =
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun hasPairing(): Boolean = preferences.getString(KEY_WRAPPED_SECRET, null) != null

    @Synchronized
    fun isVerified(): Boolean = hasPairing() && preferences.getBoolean(KEY_VERIFIED, false)

    @Synchronized
    fun generatePairingCode(roomCode: String): String {
        val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
        saveSecret(secret)
        preferences.edit().putBoolean(KEY_VERIFIED, false).apply()
        return "$PAIRING_PREFIX.${roomCode.trim()}.${encode(secret)}"
    }

    @Synchronized
    fun installPairingCode(pairingCode: String): String {
        val parts = pairingCode.trim().split('.')
        require(parts.size == 3 && parts[0] == PAIRING_PREFIX) { "Invalid pairing code." }

        val roomCode = parts[1].trim()
        require(roomCode.isNotBlank()) { "Pairing code has no room." }

        val secret = decode(parts[2])
        require(secret.size == SECRET_BYTES) { "Invalid pairing secret." }
        saveSecret(secret)
        preferences.edit().putBoolean(KEY_VERIFIED, false).apply()
        return roomCode
    }

    @Synchronized
    fun markVerified() {
        require(hasPairing()) { "Chat is not paired." }
        preferences.edit().putBoolean(KEY_VERIFIED, true).apply()
    }

    @Synchronized
    fun securityCode(): String {
        val secret = loadSecret() ?: error("Chat is not paired.")
        val digest = MessageDigest.getInstance("SHA-256").digest(secret)
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (digest[index].toLong() and 0xffL)
        }
        return String.format(Locale.US, "%06d", value % SECURITY_CODE_MODULUS)
    }

    @Synchronized
    fun pairingFingerprint(): String {
        val secret = loadSecret() ?: error("Chat is not paired.")
        return MessageDigest.getInstance("SHA-256")
            .digest(secret)
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    @Synchronized
    fun encrypt(text: String): String {
        val secret = loadSecret() ?: error("Chat is not paired.")
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(secret), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(MESSAGE_VERSION.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return "sb1:${encode(payload)}"
    }

    @Synchronized
    fun decrypt(value: String): String {
        require(value.startsWith("sb1:")) { "Encrypted chat message required." }
        val secret = loadSecret() ?: error("Chat is not paired.")
        val payload = decode(value.removePrefix("sb1:"))
        require(payload.size > 1 + IV_BYTES) { "Invalid encrypted message." }
        require(payload[0].toInt() == MESSAGE_VERSION) { "Unsupported encrypted message." }

        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(secret), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun saveSecret(secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore keys configured with setRandomizedEncryptionRequired(true)
        // must generate the GCM IV themselves during encryption.
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret)
        val wrapped = "$WRAPPED_SECRET_PREFIX:${encode(iv)}:${encode(ciphertext)}"
        preferences.edit()
            .putString(KEY_WRAPPED_SECRET, wrapped)
            .putBoolean(KEY_VERIFIED, false)
            .apply()
    }

    private fun loadSecret(): ByteArray? {
        val wrapped = preferences.getString(KEY_WRAPPED_SECRET, null) ?: return null
        val parts = wrapped.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == WRAPPED_SECRET_PREFIX) { "Invalid stored chat key." }

        val iv = decode(parts[1])
        val ciphertext = decode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

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

    private fun secretKey(secret: ByteArray): SecretKey = SecretKeySpec(secret, "AES")
}
