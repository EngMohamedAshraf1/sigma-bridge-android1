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
 * Private Chat encryption.
 *
 * sb2 is retained only for legacy/local compatibility.
 * New sb3 messages use a random 256-bit conversation key recovered by authenticated
 * members through Supabase. The key is not derived from public account identifiers.
 */
@Singleton
class ChatCrypto @Inject constructor(
    private val identity: ChatIdentity
) {
    data class DecryptedMessage(
        val text: String,
        val replyToMessageId: String? = null
    )

    private companion object {
        const val PREFIX = "sb2:"
        const val PREFIX_V2 = "sb3:"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
        const val REPLY_PREFIX = "sb_reply_v1:"
        const val REPLY_SEPARATOR = "\u0000"
        val RANDOM = SecureRandom()

        fun encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun decode(value: String): ByteArray =
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun encryptForConversationKey(text: String, keyMaterial: String): String =
        encryptWithKey(text, keyFromMaterial(keyMaterial), PREFIX_V2)

    fun encryptMessageForConversationKey(
        text: String,
        keyMaterial: String,
        replyToMessageId: String?
    ): String {
        val plaintext = if (replyToMessageId.isNullOrBlank()) {
            text
        } else {
            "$REPLY_PREFIX${replyToMessageId.trim()}$REPLY_SEPARATOR$text"
        }
        return encryptWithKey(plaintext, keyFromMaterial(keyMaterial), PREFIX_V2)
    }

    fun decryptForConversationKey(value: String, keyMaterial: String): String =
        decryptWithKey(value, keyFromMaterial(keyMaterial), PREFIX_V2).text

    fun decryptMessageForConversationKey(
        value: String,
        keyMaterial: String
    ): DecryptedMessage = decryptWithKey(value, keyFromMaterial(keyMaterial), PREFIX_V2)

    // Legacy sb2 API retained for old code/old locally cached data.
    fun encrypt(text: String): String = encryptWithKey(text, identity.conversationKey(), PREFIX)

    fun encryptMessage(text: String, replyToMessageId: String?): String {
        val plaintext = if (replyToMessageId.isNullOrBlank()) {
            text
        } else {
            "$REPLY_PREFIX${replyToMessageId.trim()}$REPLY_SEPARATOR$text"
        }
        return encryptWithKey(plaintext, identity.conversationKey(), PREFIX)
    }

    fun encryptForPartner(text: String, partnerId: String): String =
        encryptWithKey(text, identity.conversationKeyFor(partnerId), PREFIX)

    fun encryptWithPartnerMessage(text: String, partnerId: String, replyToMessageId: String?): String {
        val plaintext = if (replyToMessageId.isNullOrBlank()) {
            text
        } else {
            "$REPLY_PREFIX${replyToMessageId.trim()}$REPLY_SEPARATOR$text"
        }
        return encryptWithKey(plaintext, identity.conversationKeyFor(partnerId), PREFIX)
    }

    fun nonceFromEncrypted(value: String): String {
        val prefix = when {
            value.startsWith(PREFIX_V2) -> PREFIX_V2
            value.startsWith(PREFIX) -> PREFIX
            else -> error("Encrypted chat message required.")
        }
        val payload = decodePayload(value, prefix)
        return encode(payload.copyOfRange(1, 1 + IV_BYTES))
    }

    fun decrypt(value: String): String {
        val prefix = when {
            value.startsWith(PREFIX_V2) -> PREFIX_V2
            value.startsWith(PREFIX) -> PREFIX
            else -> error("Encrypted chat message required.")
        }
        if (prefix == PREFIX_V2) error("CONVERSATION_KEY_CONTEXT_REQUIRED")
        return decryptWithKey(value, identity.conversationKey(), PREFIX).text
    }

    fun decryptMessage(value: String): DecryptedMessage {
        val prefix = when {
            value.startsWith(PREFIX_V2) -> PREFIX_V2
            value.startsWith(PREFIX) -> PREFIX
            else -> error("Encrypted chat message required.")
        }
        if (prefix == PREFIX_V2) error("CONVERSATION_KEY_CONTEXT_REQUIRED")
        return decryptWithKey(value, identity.conversationKey(), PREFIX)
    }

    fun decryptForPartner(value: String, partnerId: String): String =
        decryptWithKey(value, identity.conversationKeyFor(partnerId), PREFIX).text

    fun decryptMessageForPartner(value: String, partnerId: String): DecryptedMessage =
        decryptWithKey(value, identity.conversationKeyFor(partnerId), PREFIX)

    private fun encryptWithKey(text: String, key: ByteArray, prefix: String): String {
        val iv = ByteArray(IV_BYTES).also(RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(VERSION)
            .put(iv)
            .put(ciphertext)
            .array()
        return prefix + encode(payload)
    }

    private fun decryptWithKey(value: String, key: ByteArray, prefix: String): DecryptedMessage {
        val payload = decodePayload(value, prefix)
        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        if (!plaintext.startsWith(REPLY_PREFIX)) {
            return DecryptedMessage(text = plaintext)
        }
        val separatorIndex = plaintext.indexOf(REPLY_SEPARATOR, REPLY_PREFIX.length)
        if (separatorIndex <= REPLY_PREFIX.length) {
            return DecryptedMessage(text = plaintext)
        }
        val replyId = plaintext.substring(REPLY_PREFIX.length, separatorIndex).trim()
        if (!replyId.matches(Regex("[0-9a-fA-F-]{36}"))) {
            return DecryptedMessage(text = plaintext)
        }
        return DecryptedMessage(
            text = plaintext.substring(separatorIndex + REPLY_SEPARATOR.length),
            replyToMessageId = replyId
        )
    }

    private fun keyFromMaterial(material: String): ByteArray {
        val normalized = material.trim()
        require(normalized.length == KEY_BYTES * 2) { "INVALID_CONVERSATION_KEY" }
        require(normalized.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "INVALID_CONVERSATION_KEY"
        }

        return ByteArray(KEY_BYTES) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun decodePayload(value: String, prefix: String): ByteArray {
        require(value.startsWith(prefix)) { "Encrypted chat message required." }
        val payload = decode(value.removePrefix(prefix))
        require(payload.size > 1 + IV_BYTES && payload[0] == VERSION) {
            "Invalid encrypted message."
        }
        return payload
    }
}
