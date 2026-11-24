package io.github.tianli0.switchtether.remote.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receiver side of RFC 8291 (Message Encryption for Web Push, `aes128gcm`).
 *
 * The phone creates a P-256 key pair and a 16-byte auth secret per push registration and gives the
 * public half to the desktop with the UnifiedPush endpoint. Push messages are decrypted only to
 * check that they come from the desktop (a message from anyone else does not decrypt); their
 * content is never used for anything but "connect now".
 */
object WebPushCrypto {
    private val b64d = Base64.getUrlDecoder()
    private val b64e = Base64.getUrlEncoder().withoutPadding()

    fun encode(bytes: ByteArray): String = b64e.encodeToString(bytes)
    fun decode(s: String): ByteArray = b64d.decode(s.trim().trimEnd('='))

    /** Receiver keys, base64url: PKCS#8 private key, uncompressed public key, auth secret. */
    data class Keys(val privatePkcs8: String, val publicKey: String, val auth: String)

    fun newKeys(random: SecureRandom = SecureRandom()): Keys {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = g.generateKeyPair()
        val auth = ByteArray(16).also(random::nextBytes)
        return Keys(encode(kp.private.encoded), encode(uncompressed(kp.public as ECPublicKey)), encode(auth))
    }

    private val curve: ECParameterSpec by lazy {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        (g.generateKeyPair().public as ECPublicKey).params
    }

    private fun fixed32(b: BigInteger): ByteArray {
        val raw = b.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun uncompressed(k: ECPublicKey): ByteArray = byteArrayOf(0x04) + fixed32(k.w.affineX) + fixed32(k.w.affineY)

    private fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    fun privateFromPkcs8(b64: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(b64)))

    /** A raw 32-byte private scalar (test vectors). */
    fun privateFromScalar(raw: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), curve))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-SHA256 with an output of at most 32 bytes (one block is all RFC 8291 needs). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray =
        hmac(hmac(salt, ikm), info + byteArrayOf(1)).copyOf(len)

    /**
     * Decrypt a single-record `aes128gcm` message. Returns null when it does not decrypt (not for
     * these keys, damaged, or not RFC 8291 at all).
     */
    fun decrypt(message: ByteArray, privateKey: PrivateKey, publicKey: ByteArray, auth: ByteArray): ByteArray? = runCatching {
        if (message.size < 21) return null
        val salt = message.copyOfRange(0, 16)
        val idLen = message[20].toInt() and 0xff
        if (idLen != 65 || message.size < 21 + idLen + 17) return null
        val asPublic = message.copyOfRange(21, 21 + idLen)
        val ct = message.copyOfRange(21 + idLen, message.size)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey(asPublic), true)
        val ecdh = ka.generateSecret()
        val ikm = hkdf(auth, ecdh, "WebPush: info".toByteArray() + byteArrayOf(0) + publicKey + asPublic, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val plain = c.doFinal(ct)
        // The last record ends with the delimiter 0x02, optionally followed by zero padding.
        var end = plain.size - 1
        while (end >= 0 && plain[end] == 0.toByte()) end--
        if (end < 0 || plain[end] != 0x02.toByte()) return null
        plain.copyOf(end)
    }.getOrNull()

    fun decrypt(message: ByteArray, keys: Keys): ByteArray? =
        decrypt(message, privateFromPkcs8(keys.privatePkcs8), decode(keys.publicKey), decode(keys.auth))
}
