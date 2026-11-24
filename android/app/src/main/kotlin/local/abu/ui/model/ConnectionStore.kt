package local.abu.ui.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.abu.data.DEFAULT_SERVER_BASE_URL
import local.abu.data.normalizeServerBaseUrl
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredConnection(
    val serverUrl: String = DEFAULT_SERVER_BASE_URL,
    val token: String = "",
)

internal interface ConnectionStorage {
    fun load(): StoredConnection
    fun save(connection: StoredConnection): Boolean
    fun clearToken()
}

internal class InMemoryConnectionStorage(
    initial: StoredConnection = StoredConnection(),
) : ConnectionStorage {
    private var current = initial
    override fun load(): StoredConnection = current
    override fun save(connection: StoredConnection): Boolean {
        current = connection
        return true
    }
    override fun clearToken() {
        current = current.copy(token = "")
    }
}

/**
 * Persists the server URL and an AndroidKeyStore AES-GCM encrypted bearer token in private storage.
 * If the keystore entry or ciphertext is unreadable, it fails closed to an empty token instead of
 * falling back to plaintext storage.
 */
internal class AndroidConnectionStorage(context: Context) : ConnectionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredConnection {
        val url = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_BASE_URL
        val ciphertext = prefs.getString(KEY_TOKEN_ENC, null)
        val iv = prefs.getString(KEY_TOKEN_IV, null)
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) {
            return StoredConnection(serverUrl = url, token = "")
        }
        val token = decrypt(ciphertext, iv)
        if (token == null) {
            clearToken()
            return StoredConnection(serverUrl = url, token = "")
        }
        return StoredConnection(serverUrl = url, token = token)
    }

    override fun save(connection: StoredConnection): Boolean {
        val editor = prefs.edit().putString(KEY_SERVER_URL, connection.serverUrl)
        if (connection.token.isBlank()) {
            editor.remove(KEY_TOKEN_ENC).remove(KEY_TOKEN_IV).apply()
            return true
        }
        val encrypted = encrypt(connection.token) ?: return false
        editor
            .putString(KEY_TOKEN_ENC, encrypted.first)
            .putString(KEY_TOKEN_IV, encrypted.second)
            .apply()
        return true
    }

    override fun clearToken() {
        prefs.edit().remove(KEY_TOKEN_ENC).remove(KEY_TOKEN_IV).apply()
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
    }.getOrNull()

    private fun encrypt(plaintext: String): Pair<String, String>? = runCatching {
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        val ct = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        ct to iv
    }.getOrNull()

    private fun decrypt(ciphertext: String, ivText: String): String? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val key = store.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
        val iv = Base64.decode(ivText, Base64.NO_WRAP)
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.doFinal(bytes).decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "abu_connection"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_TOKEN_IV = "token_iv"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "abu_connection_token_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal sealed interface ConnectionApplyResult {
    data class Applied(val serverUrl: String, val token: String, val persisted: Boolean) : ConnectionApplyResult
    data class Invalid(val message: String) : ConnectionApplyResult
}

internal class ConnectionPreference(
    private val storage: ConnectionStorage,
    val allowCleartext: Boolean,
) {
    private val initial = storage.load().let { stored ->
        val safeUrl = normalizeServerBaseUrl(stored.serverUrl, allowCleartext)
            ?: if (allowCleartext) DEFAULT_SERVER_BASE_URL else ""
        val safeToken = stored.token.trim().takeIf { it.length >= 32 && safeUrl.isNotEmpty() }.orEmpty()
        StoredConnection(serverUrl = safeUrl, token = safeToken)
    }

    var serverUrl by mutableStateOf(initial.serverUrl)
        private set
    var token by mutableStateOf(initial.token)
        private set
    var persisted by mutableStateOf(initial.token.isNotEmpty())
        private set

    fun apply(rawUrl: String, rawToken: String): ConnectionApplyResult {
        val normalizedUrl = normalizeServerBaseUrl(rawUrl, allowCleartext)
            ?: return ConnectionApplyResult.Invalid(
                if (allowCleartext) "服务地址无效，请填写如 http://127.0.0.1:8080 或 https://host:port"
                else "正式版本仅支持 https:// 开头的安全服务地址",
            )
        val trimmedToken = rawToken.trim()
        if (trimmedToken.length < 32) {
            return ConnectionApplyResult.Invalid("访问令牌长度至少需要 32 个字符")
        }
        val saved = storage.save(StoredConnection(normalizedUrl, trimmedToken))
        serverUrl = normalizedUrl
        token = trimmedToken
        persisted = saved
        return ConnectionApplyResult.Applied(normalizedUrl, trimmedToken, saved)
    }

    fun clearToken() {
        storage.clearToken()
        token = ""
        persisted = false
    }
}

/**
 * Partitions local mini-app caches by canonical server origin in addition to the credential hash,
 * so switching between servers that happen to share a token never exposes one server's cached
 * bundle or offline snapshot under another server.
 */
internal fun serverScopedCacheDir(baseDir: File, namespace: String, serverUrl: String): File {
    val canonical = (normalizeServerBaseUrl(serverUrl, allowCleartext = true) ?: serverUrl.trim())
        .lowercase()
    if (canonical.isEmpty()) {
        return baseDir.resolve("$namespace-unconfigured")
    }
    if (canonical == DEFAULT_SERVER_BASE_URL) {
        return baseDir.resolve(namespace)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
    return baseDir.resolve("$namespace-servers").resolve(digest)
}
