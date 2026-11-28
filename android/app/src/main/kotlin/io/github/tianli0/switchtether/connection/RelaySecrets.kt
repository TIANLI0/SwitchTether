package io.github.tianli0.switchtether.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.tianli0.switchtether.data.PairedDesktop
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Relay access token (`rt`) and relay key (`rk`) of the paired computer (RELAY.md §2.0 / §5). */
@Serializable
data class RelaySecret(val token: String? = null, val key: String)

/** Seals / opens [RelaySecret] for [PairedDesktop.relaySecret]. */
interface RelaySecrets {
    fun seal(secret: RelaySecret): String?
    fun open(desktop: PairedDesktop): RelaySecret?

    /** No relay credentials available (tests, or before pairing). */
    object None : RelaySecrets {
        override fun seal(secret: RelaySecret): String? = null
        override fun open(desktop: PairedDesktop): RelaySecret? = null
    }
}

/**
 * The relay token and key are secrets: they are stored only encrypted with a non-exportable
 * AndroidKeyStore AES-256-GCM key, which is deleted with every other key on unpair. Without the
 * key (or after a factory reset of the keystore) the relay is simply not used.
 */
object KeystoreRelaySecrets : RelaySecrets {
    private const val ALIAS = "switchtether_relay_secrets"
    private const val PROVIDER = "AndroidKeyStore"
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Deletes the AndroidKeyStore key on unpair / revocation so sealed relay secrets can never be opened again. */
    @Synchronized
    fun destroy() {
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    override fun seal(secret: RelaySecret): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(create = true))
        val ct = c.doFinal(json.encodeToString(RelaySecret.serializer(), secret).toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(c.iv + ct)
    }.getOrNull()

    override fun open(desktop: PairedDesktop): RelaySecret? = runCatching {
        val blob = Base64.getDecoder().decode(desktop.relaySecret ?: return null)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(create = false) ?: return null, GCMParameterSpec(128, blob, 0, 12))
        json.decodeFromString(RelaySecret.serializer(), String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8))
    }.getOrNull()
}
