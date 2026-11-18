package io.github.tianli0.switchtether.remote.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion

/**
 * The phone's TLS client identity: a non-exportable P-256 key (Android Keystore in production,
 * a software key in JVM tests) and its self-signed certificate. The desktop identifies the device
 * by SHA-256 of [certificate] DER.
 */
interface ClientIdentity {
    val alias: String
    val privateKey: PrivateKey
    val certificate: X509Certificate
    val fingerprint: String get() = Fingerprints.sha256Hex(certificate.encoded)
}

/** Presents exactly one client identity for every TLS handshake (mutual TLS is mandatory, §1). */
class SingleIdentityKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        arrayOf(identity.alias)

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String =
        identity.alias

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String =
        identity.alias

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == identity.alias) arrayOf(identity.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == identity.alias) identity.privateKey else null
}

/**
 * Accepts ONLY a server whose leaf certificate DER hashes (SHA-256) to the pinned fingerprint from
 * the pairing QR code. No CA, no expiry/hostname checks: the desktop certificate is self-signed and
 * the pin is the trust anchor. Comparison is constant-time.
 */
class PinnedServerTrustManager(pinnedFingerprintHex: String) : X509ExtendedTrustManager() {
    private val pinned: ByteArray = Fingerprints.fromHex(pinnedFingerprintHex.lowercase())

    init {
        require(pinned.size == 32) { "fingerprint must be a SHA-256 (32 bytes)" }
    }

    fun matches(leaf: X509Certificate): Boolean =
        Fingerprints.constantTimeEquals(Fingerprints.sha256(leaf.encoded), pinned)

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty server certificate chain")
        if (!matches(leaf)) throw PinMismatchException()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client role not supported")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client role not supported")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client role not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Trust on first use, for pairing with a computer whose certificate is not known in advance
 * (address typed by the user, pairing code instead of QR code). Accepts any server certificate and
 * records the leaf fingerprint; the caller then pins it for everything that follows, and the user
 * must compare the verification code on both screens.
 */
class RecordingServerTrustManager : X509ExtendedTrustManager() {
    @Volatile var fingerprint: String? = null
        private set

    private fun record(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty server certificate chain")
        fingerprint = Fingerprints.sha256Hex(leaf.encoded)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = record(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = record(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = record(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client role not supported")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client role not supported")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client role not supported")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The desktop presented a certificate that does not match the pinned fingerprint. */
class PinMismatchException : CertificateException("server certificate does not match the pinned fingerprint")

object TlsClientFactory {
    const val IDLE_KEEP_ALIVE_SECONDS = 20L

    /** TLS 1.3 only (§1). Only the TLS 1.3 AEAD suites are listed. */
    val TLS13_ONLY: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_3)
        .cipherSuites(
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        )
        .build()

    /**
     * Builds the single OkHttpClient used for REST and WebSockets towards one desktop.
     *
     * HostnameVerifier accepts any host on purpose: the desktop is reached via LAN/Tailscale IPs or
     * MagicDNS names that are not in its self-signed certificate, and the trust decision is already
     * made by [PinnedServerTrustManager] (exact leaf-certificate pin). Hostname matching would add
     * nothing on top of an exact certificate pin and would break IP-based connections.
     */
    fun create(
        identity: ClientIdentity,
        pinnedFingerprint: String,
        base: OkHttpClient? = null,
        connectTimeoutMs: Long = 5_000,
        readTimeoutMs: Long = 20_000,
    ): OkHttpClient {
        return build(identity, PinnedServerTrustManager(pinnedFingerprint), base, connectTimeoutMs, readTimeoutMs)
    }

    /**
     * Client that accepts any server certificate and records it in [recorder] (pairing by address /
     * code only; see [RecordingServerTrustManager]). Never used after pairing.
     */
    fun createRecording(
        identity: ClientIdentity,
        recorder: RecordingServerTrustManager,
        base: OkHttpClient? = null,
        connectTimeoutMs: Long = 5_000,
    ): OkHttpClient = build(identity, recorder, base, connectTimeoutMs, 20_000)

    private fun build(
        identity: ClientIdentity,
        trustManager: X509ExtendedTrustManager,
        base: OkHttpClient?,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(SingleIdentityKeyManager(identity)), arrayOf(trustManager), SecureRandom())
        val builder = base?.newBuilder() ?: OkHttpClient.Builder()
        return builder
            // The desktop (hyper) drops keep-alive connections that stay idle for 30 s
            // (header_read_timeout). Evict ours earlier so a pooled connection is never stale.
            .connectionPool(ConnectionPool(4, IDLE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(TLS13_ONLY))
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // never silently replay requests (§5)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
