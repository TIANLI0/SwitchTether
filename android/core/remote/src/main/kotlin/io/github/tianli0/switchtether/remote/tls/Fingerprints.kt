package io.github.tianli0.switchtether.remote.tls

import java.security.MessageDigest

/** Fingerprint helpers (docs/PROTOCOL.md §1: lowercase hex SHA-256 of the leaf certificate DER). */
object Fingerprints {
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = toHex(sha256(bytes))

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Constant-time comparison of two fingerprints (bytes). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** `ab12 cd34 …` — groups of 4 for display. */
    fun grouped(fp: String): String = fp.lowercase().chunked(4).joinToString(" ")

    /** Short form: first 16 hex chars, grouped. */
    fun short(fp: String): String = grouped(fp.take(16))

    /**
     * Six-digit short authentication string, computed identically on the desktop
     * (`util::verification_code`): SHA-256("switchtether-sas-v1|<serverFp>|<clientFp>"),
     * first 4 bytes big-endian mod 1 000 000. Both screens show it so the user can compare.
     */
    fun verificationCode(serverFp: String, clientFp: String): String {
        val d = sha256("switchtether-sas-v1|$serverFp|$clientFp".toByteArray(Charsets.UTF_8))
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }
}
