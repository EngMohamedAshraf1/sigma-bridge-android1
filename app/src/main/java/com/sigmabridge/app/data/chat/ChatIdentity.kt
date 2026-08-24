package com.sigmabridge.app.data.chat

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sigmabridge.app.domain.chat.ChatProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.KeyAgreement
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

@Singleton
class ChatIdentity @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val userId: String by lazy {
        preferences.getString(KEY_USER_ID, null) ?: generateAndStoreUserId()
    }

    var username: String
        get() = preferences.getString(KEY_USERNAME, null) ?: defaultUsername().also { updateUsername(it) }
        private set(value) { preferences.edit().putString(KEY_USERNAME, value).apply() }

    var partner: ChatProfile?
        get() = preferences.getString(KEY_PARTNER_PROFILE, null)?.let { raw ->
            runCatching { json.decodeFromString(ChatProfile.serializer(), raw) }.getOrNull()
        }
        private set(value) {
            val raw = value?.let { json.encodeToString(ChatProfile.serializer(), it) }
            if (raw == null) preferences.edit().remove(KEY_PARTNER_PROFILE).apply()
            else preferences.edit().putString(KEY_PARTNER_PROFILE, raw).apply()
        }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    init { ensureKeyPair() }

    fun updateUsername(value: String) {
        val normalized = value.trim().removePrefix("@").lowercase()
        require(Regex("^[a-z0-9_]{5,32}$").matches(normalized)) {
            "Username must be 5-32 characters: a-z, 0-9, _."
        }
        username = normalized
    }

    fun updatePartner(profile: ChatProfile) {
        require(profile.userId != userId) { "You cannot chat with yourself." }
        partner = profile
    }

    fun clearPartner() { partner = null }

    fun myProfile(): ChatProfile = ChatProfile(username, userId, publicKeyBase64())

    fun conversationTopic(): String {
        val p = partner ?: error("Choose a user first.")
        val combined = listOf(userId, p.userId).sorted().joinToString("|")
        return "sigma-bridge-chat-${sha256(combined).take(40)}"
    }

    fun conversationKey(): ByteArray {
        val p = partner ?: error("Choose a user first.")
        val remote = decodePublicKey(p.publicKey)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(remote, true)
        return MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret())
    }

    private fun ensureKeyPair() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .build()
            )
        } else {
            val now = Date()
            val end = Date(now.time + LEGACY_KEY_VALIDITY_MS)
            val legacySpec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS)
                .setSubject(X500Principal("CN=SigmaBridgeChat"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(now)
                .setEndDate(end)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
            generator.initialize(legacySpec)
        }

        generator.generateKeyPair()
    }

    private fun publicKeyBase64(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey as ECPublicKey
        return Base64.encodeToString(publicKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decodePublicKey(value: String): java.security.PublicKey {
        val bytes = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
        val spec = java.security.spec.X509EncodedKeySpec(bytes)
        return java.security.KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun generateAndStoreUserId(): String {
        val bytes = ByteArray(12).also(SecureRandom()::nextBytes)
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val id = buildString { for (b in bytes) append(alphabet[(b.toInt() and 0xFF) % alphabet.length]) }
        val formatted = "SB-" + id.chunked(4).joinToString("-")
        preferences.edit().putString(KEY_USER_ID, formatted).apply()
        return formatted
    }

    private fun defaultUsername(): String = "sigma_${userId.replace("-", "").takeLast(8).lowercase()}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_identity"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_PARTNER_PROFILE = "partner_profile"
        const val KEY_ALIAS = "SigmaBridgeChatEcdhKey"
        const val KEYSTORE = "AndroidKeyStore"
        const val LEGACY_KEY_VALIDITY_MS = 10L * 365L * 24L * 60L * 60L * 1000L
    }
}
