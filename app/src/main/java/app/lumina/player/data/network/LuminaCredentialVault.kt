package app.lumina.player.data.network

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class NetworkCredentials(
    val username: String,
    val password: String,
    val domain: String? = null
)

/**
 * Hardware-backed `AndroidKeyStore` AES-256-GCM vault for SMB / NAS / FTP share credentials.
 *
 * - Never stores plaintext passwords on disk.
 * - Uses per-record random 96-bit GCM IVs and 128-bit authentication tags.
 * - Normalizes server authorities (`smb://nas.local/Movies/4K` -> `smb://nas.local`) so one login
 *   seamlessly authenticates recursive folder browsing and video playback across the share.
 */
@Singleton
class LuminaCredentialVault @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val _savedServerKeys = MutableStateFlow<Set<String>>(emptySet())
    val savedServerKeys: StateFlow<Set<String>> = _savedServerKeys.asStateFlow()

    init {
        refreshSavedKeys()
    }

    fun hasCredentialsFor(uriOrHost: String): Boolean {
        val key = normalizeServerKey(uriOrHost)
        return key.isNotEmpty() && prefs.contains(prefKeyFor(key))
    }

    fun getCredentialsFor(uriOrHost: String): NetworkCredentials? {
        val key = normalizeServerKey(uriOrHost)
        if (key.isEmpty()) return null
        val encoded = prefs.getString(prefKeyFor(key), null) ?: return null
        return runCatching {
            val secretKey = getOrCreateHardwareSecretKey() ?: return null
            val decryptedJson = decryptAesGcmPayload(encoded, secretKey) ?: return null
            val obj = JSONObject(decryptedJson)
            val user = obj.optString("u", "")
            val pass = obj.optString("p", "")
            val domain = obj.optString("d", "").takeIf { it.isNotBlank() }
            if (user.isBlank() && pass.isBlank()) null else NetworkCredentials(user, pass, domain)
        }.getOrNull()
    }

    fun saveCredentialsFor(uriOrHost: String, credentials: NetworkCredentials) {
        val key = normalizeServerKey(uriOrHost)
        if (key.isEmpty()) return
        runCatching {
            val secretKey = getOrCreateHardwareSecretKey() ?: return
            val json = JSONObject().apply {
                put("u", credentials.username)
                put("p", credentials.password)
                if (!credentials.domain.isNullOrBlank()) {
                    put("d", credentials.domain)
                }
            }.toString()
            val encrypted = encryptAesGcmPayload(json, secretKey)
            prefs.edit().putString(prefKeyFor(key), encrypted).apply()
            refreshSavedKeys()
        }
    }

    fun removeCredentialsFor(uriOrHost: String) {
        val key = normalizeServerKey(uriOrHost)
        if (key.isEmpty()) return
        prefs.edit().remove(prefKeyFor(key)).apply()
        refreshSavedKeys()
    }

    private fun refreshSavedKeys() {
        runCatching {
            val keys = prefs.all.keys
                .filter { it.startsWith(KEY_PREFIX) }
                .map { it.removePrefix(KEY_PREFIX) }
                .toSet()
            _savedServerKeys.value = keys
        }
    }

    private fun getOrCreateHardwareSecretKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return@runCatching existing.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "lumina_lan_aes256_gcm_v1"
        private const val PREFS_FILE_NAME = "lumina_encrypted_lan_vault"
        private const val KEY_PREFIX = "srv_"
        private const val GCM_TAG_BITS = 128

        private fun prefKeyFor(normalizedServerKey: String): String = "$KEY_PREFIX$normalizedServerKey"

        /**
         * Normalizes any remote URI (`smb://user@NAS-01:445/Movies/Clip.mkv`) into its canonical
         * scheme + host[:port] key (`smb://nas-01:445`) without leaking inline userinfo.
         */
        fun normalizeServerKey(rawUriOrHost: String): String {
            val trimmed = rawUriOrHost.trim()
            if (trimmed.isEmpty()) return ""
            val schemeSeparator = trimmed.indexOf("://")
            if (schemeSeparator <= 0) {
                return "smb://${trimmed.substringBefore('/').substringAfter('@').lowercase(Locale.ROOT)}"
            }
            val scheme = trimmed.substring(0, schemeSeparator).lowercase(Locale.ROOT)
            val afterScheme = trimmed.substring(schemeSeparator + 3)
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            val hostPort = authority.substringAfterLast('@').lowercase(Locale.ROOT)
            return if (hostPort.isEmpty()) "" else "$scheme://$hostPort"
        }

        internal fun encryptAesGcmBytes(plaintext: ByteArray, secretKey: SecretKey): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            return iv to ciphertext
        }

        internal fun decryptAesGcmBytes(iv: ByteArray, ciphertext: ByteArray, secretKey: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }

        private fun encryptAesGcmPayload(plaintext: String, secretKey: SecretKey): String {
            val (iv, ciphertext) = encryptAesGcmBytes(
                plaintext.toByteArray(StandardCharsets.UTF_8),
                secretKey
            )
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            return "$ivB64:$ctB64"
        }

        private fun decryptAesGcmPayload(encoded: String, secretKey: SecretKey): String? {
            val parts = encoded.split(':', limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val plainBytes = decryptAesGcmBytes(iv, ciphertext, secretKey)
            return String(plainBytes, StandardCharsets.UTF_8)
        }

        /**
         * Resolves credentials from an explicit override or the encrypted vault for [uri].
         */
        fun resolveEffectiveCredentials(
            vault: LuminaCredentialVault?,
            uri: Uri,
            explicitUser: String?,
            explicitPassword: String?,
            explicitDomain: String? = null
        ): NetworkCredentials? {
            if (!explicitUser.isNullOrBlank() || !explicitPassword.isNullOrBlank()) {
                return NetworkCredentials(
                    username = explicitUser.orEmpty(),
                    password = explicitPassword.orEmpty(),
                    domain = explicitDomain
                )
            }
            return vault?.getCredentialsFor(uri.toString())
        }
    }
}
