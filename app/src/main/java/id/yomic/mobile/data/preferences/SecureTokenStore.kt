package id.yomic.mobile.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MalAuthSnapshot(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMs: Long = 0L,
    val username: String = ""
) {
    val isConnected: Boolean get() = accessToken.isNotBlank()
}

interface MalTokenStorage {
    fun getMalAuth(): MalAuthSnapshot
    fun saveMalAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
        username: String = getMalAuth().username
    )
    fun updateMalUsername(username: String)
    fun clearMalAuth()
}

/**
 * Stores sensitive OAuth tokens (MyAnimeList) encrypted using Android Keystore AES-256-GCM.
 * Mirrors Windows DPAPI token protection in Yomic Desktop's SettingsService.cs.
 */
class SecureTokenStore(context: Context) : MalTokenStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _malAuthFlow = MutableStateFlow(readSnapshot())
    val malAuthFlow: StateFlow<MalAuthSnapshot> = _malAuthFlow.asStateFlow()

    override fun getMalAuth(): MalAuthSnapshot = _malAuthFlow.value

    override fun saveMalAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
        username: String
    ) {
        prefs.edit()
            .putString(KEY_ACCESS, encrypt(accessToken))
            .putString(KEY_REFRESH, encrypt(refreshToken))
            .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
            .putString(KEY_USERNAME, username)
            .apply()
        _malAuthFlow.value = MalAuthSnapshot(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = expiresAtEpochMs,
            username = username
        )
    }

    override fun updateMalUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
        _malAuthFlow.value = _malAuthFlow.value.copy(username = username)
    }

    override fun clearMalAuth() {
        prefs.edit().clear().apply()
        _malAuthFlow.value = MalAuthSnapshot()
    }

    private fun readSnapshot(): MalAuthSnapshot {
        val access = decrypt(prefs.getString(KEY_ACCESS, null))
        val refresh = decrypt(prefs.getString(KEY_REFRESH, null))
        val expires = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val user = prefs.getString(KEY_USERNAME, "") ?: ""
        return MalAuthSnapshot(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = expires,
            username = user
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "GCM:$ivB64:$dataB64"
        }.getOrElse { "" }
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        return runCatching {
            if (!encoded.startsWith("GCM:")) return ""
            val parts = encoded.split(":", limit = 3)
            if (parts.size != 3) return ""
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    companion object {
        private const val PREFS_NAME = "yomic_secure_tokens"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "yomic_mal_token_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_ACCESS = "mal_access_enc"
        private const val KEY_REFRESH = "mal_refresh_enc"
        private const val KEY_EXPIRES_AT = "mal_expires_at"
        private const val KEY_USERNAME = "mal_username"
    }
}
