package io.github.tianli0.switchtether.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small blobs the app keeps on disk (AES-256-GCM, a fresh random IV per blob). [aad] binds a
 * blob to what it is for (for example the computer it belongs to), so a file copied into another
 * place does not open. [destroy] deletes the key: whatever was sealed with it can no longer be read
 * (crypto-erase, also when deleting the files failed).
 */
interface BlobCipher {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray?
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray?
    fun destroy()
}

/** AES-GCM with the key [keyFor] returns. Format: version (1) | IV (12) | ciphertext + tag (16). */
open class AesGcmBlobCipher(
    private val keyFor: (create: Boolean) -> SecretKey?,
    private val onDestroy: () -> Unit = {},
) : BlobCipher {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray? = runCatching {
        val key = keyFor(true) ?: return null
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key) // the provider picks the IV (Keystore requires it)
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        val iv = c.iv
        if (iv.size != IV_BYTES) return null
        byteArrayOf(VERSION) + iv + ct
    }.getOrNull()

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? = runCatching {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != VERSION) return null
        val key = keyFor(false) ?: return null
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
        c.updateAAD(aad)
        c.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }.getOrNull()

    override fun destroy() {
        runCatching(onDestroy)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/**
 * [BlobCipher] whose key is a non-exportable AndroidKeyStore AES-256 key ([alias]); created on the
 * first seal, deleted by [destroy] (unpair, revoke, "clear caches").
 */
class KeystoreBlobCipher(alias: String) : AesGcmBlobCipher(
    keyFor = { create -> keystoreKey(alias, create) },
    onDestroy = { deleteKey(alias) },
) {
    private companion object {
        const val PROVIDER = "AndroidKeyStore"

        @Synchronized
        fun keystoreKey(alias: String, create: Boolean): SecretKey? {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            if (!create) return null
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        @Synchronized
        fun deleteKey(alias: String) {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }
    }
}
