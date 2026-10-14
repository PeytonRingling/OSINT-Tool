package vip.ringling.recon.data.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vip.ringling.recon.domain.ApiKeyId

/** Where API keys live. Values are never logged and never leave the device. */
interface ApiKeys {
    val keys: StateFlow<Map<ApiKeyId, String>>
    fun get(id: ApiKeyId): String? = keys.value[id]?.takeIf { it.isNotBlank() }
    fun set(id: ApiKeyId, value: String)
}

/** Test/preview implementation. */
class InMemoryApiKeys(initial: Map<ApiKeyId, String> = emptyMap()) : ApiKeys {
    private val state = MutableStateFlow(initial)
    override val keys: StateFlow<Map<ApiKeyId, String>> = state.asStateFlow()
    override fun set(id: ApiKeyId, value: String) = state.update { if (value.isBlank()) it - id else it + (id to value.trim()) }
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Only ciphertext (IV + data) is written to
 * SharedPreferences, so keys are never stored in plaintext.
 */
@Singleton
class KeystoreApiKeys @Inject constructor(@ApplicationContext context: Context) : ApiKeys {
    private val prefs = context.getSharedPreferences("recon_secrets", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val keys: StateFlow<Map<ApiKeyId, String>> = state.asStateFlow()

    override fun set(id: ApiKeyId, value: String) {
        val v = value.trim()
        if (v.isEmpty()) prefs.edit().remove(id.name).apply() else prefs.edit().putString(id.name, encrypt(v)).apply()
        state.update { if (v.isEmpty()) it - id else it + (id to v) }
    }

    private fun load(): Map<ApiKeyId, String> = ApiKeyId.entries.mapNotNull { id ->
        val blob = prefs.getString(id.name, null) ?: return@mapNotNull null
        runCatching { id to decrypt(blob) }.getOrNull() // unreadable (e.g. keystore reset) → treated as unset
    }.toMap()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN)) }
        return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "recon_api_keys_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
