package io.github.tianli0.switchtether.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AesGcmBlobCipherTest {
    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripWithMatchingAad() {
        var key: SecretKey? = freshKey()
        val cipher = AesGcmBlobCipher(keyFor = { key }, onDestroy = { key = null })
        val plain = "switchtether-secret-payload".toByteArray(Charsets.UTF_8)
        val aad = "server-1".toByteArray(Charsets.UTF_8)

        val sealed = cipher.seal(plain, aad)
        assertNotNull(sealed)
        assertArrayEquals(plain, cipher.open(sealed!!, aad))
    }

    @Test
    fun rejectsWrongAad() {
        val key = freshKey()
        val cipher = AesGcmBlobCipher(keyFor = { key })
        val sealed = cipher.seal("hello".toByteArray(), "server-a".toByteArray())!!

        assertNull(cipher.open(sealed, "server-b".toByteArray()))
    }

    @Test
    fun rejectsTamperedCiphertextAndBadHeader() {
        val key = freshKey()
        val cipher = AesGcmBlobCipher(keyFor = { key })
        val aad = "aad".toByteArray()
        val sealed = cipher.seal("payload".toByteArray(), aad)!!

        val tampered = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte() }
        assertNull(cipher.open(tampered, aad))

        val wrongVersion = sealed.copyOf().apply { this[0] = 2 }
        assertNull(cipher.open(wrongVersion, aad))

        assertNull(cipher.open(ByteArray(10), aad))
    }

    @Test
    fun destroyCryptoErasesSealedData() {
        var key: SecretKey? = freshKey()
        val cipher = AesGcmBlobCipher(
            keyFor = { create ->
                if (key == null && create) key = freshKey()
                key
            },
            onDestroy = { key = null },
        )
        val aad = "server-1".toByteArray()
        val sealed = cipher.seal("data".toByteArray(), aad)!!
        assertNotNull(cipher.open(sealed, aad))

        cipher.destroy()
        assertNull(cipher.open(sealed, aad))
    }
}
