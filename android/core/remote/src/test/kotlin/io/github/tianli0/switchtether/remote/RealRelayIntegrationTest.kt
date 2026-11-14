package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.relay.RELAY_HOST
import io.github.tianli0.switchtether.remote.relay.RelayConfig
import io.github.tianli0.switchtether.remote.relay.RelayException
import io.github.tianli0.switchtether.remote.relay.RelayProtocol
import io.github.tianli0.switchtether.remote.relay.relayBaseClient
import io.github.tianli0.switchtether.remote.pairing.causeChain
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in: runs against the real Go relay (relay/cmd/switchtether-relay). Start it and set
 * `SWITCHTETHER_RELAY_TEST=tcp://127.0.0.1:47700,ws://127.0.0.1:47780/v1/tunnel`.
 * A JVM fake desktop registers with LISTEN/CHALLENGE/AUTH (ECDSA P-256 over the RELAY.md §2.1
 * message), accepts tunnels with ACCEPT and serves `GET /v1/hello` over its own TLS server with
 * required client certificates — so the phone's pinned mutual TLS runs end to end through it.
 */
class RealRelayIntegrationTest {
    private val urls = System.getenv("SWITCHTETHER_RELAY_TEST")?.split(',')?.map { it.trim() }.orEmpty()
    private val serverCert = HeldCertificate.Builder().ecdsa256().commonName("desktop").build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().commonName("phone").build()
    private val identity = object : ClientIdentity {
        override val alias = "phone"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private val fp = Fingerprints.sha256Hex(serverCert.certificate.encoded)
    private val token = "relay-token_1"
    private var control: Socket? = null
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Before
    fun setUp() = assumeTrue("set SWITCHTETHER_RELAY_TEST to run against a real relay", urls.isNotEmpty())

    @After
    fun tearDown() { runCatching { control?.close() } }

    /** The relay's key (RELAY.md §2.0): `SWITCHTETHER_RELAY_KEY`, base64url of 32 bytes. */
    private val relayKey: String = System.getenv("SWITCHTETHER_RELAY_KEY") ?: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private fun writeLine(s: Socket, line: String) {
        s.getOutputStream().write((line + "\n").toByteArray(Charsets.US_ASCII))
        s.getOutputStream().flush()
    }

    /** Fake desktop: control connection + one TLS server per accepted tunnel. */
    private fun startDesktop(tcpHost: String, tcpPort: Int) {
        val c = Socket(tcpHost, tcpPort)
        control = c
        writeLine(c, "ST-RELAY/1 LISTEN $fp")
        val challenge = RelayProtocol.readLine(c.getInputStream())
        assertTrue(challenge, challenge.startsWith("CHALLENGE "))
        val nonce = challenge.removePrefix("CHALLENGE ").trim()
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(serverCert.keyPair.private)
            update("switchtether-relay-v1\n$nonce\n$fp".toByteArray(Charsets.UTF_8))
        }.sign()
        val mac = RelayProtocol.mac(RelayConfig.decodeKey(relayKey)!!, "listen", nonce, fp)
        writeLine(c, "AUTH ${b64.encodeToString(serverCert.certificate.encoded)} ${b64.encodeToString(sig)} $mac")
        assertEquals("OK", RelayProtocol.readLine(c.getInputStream()))
        val tls = HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(clientHeld.certificate).build()
        thread(isDaemon = true) {
            while (true) {
                val line = runCatching { RelayProtocol.readLine(c.getInputStream()) }.getOrNull() ?: break
                val parts = line.split(' ')
                when (parts[0]) {
                    "PING" -> synchronized(c) { writeLine(c, "PONG") }
                    "INCOMING" -> {
                        val conn = parts[1]
                        if (parts.getOrNull(2) != token) {
                            synchronized(c) { writeLine(c, "REJECT $conn") }
                            continue
                        }
                        thread(isDaemon = true) {
                            val data = Socket(tcpHost, tcpPort)
                            writeLine(data, "ST-RELAY/1 ACCEPT $fp $conn")
                            if (RelayProtocol.readLine(data.getInputStream()) != "OK") { data.close(); return@thread }
                            val ssl = tls.sslSocketFactory().createSocket(data, tcpHost, tcpPort, true) as SSLSocket
                            ssl.useClientMode = false
                            ssl.needClientAuth = true
                            ssl.startHandshake()
                            // Minimal HTTP/1.1: answer every request with hello JSON.
                            val input = ssl.inputStream.bufferedReader(Charsets.ISO_8859_1)
                            while (true) {
                                val requestLine = input.readLine() ?: break
                                if (requestLine.isEmpty()) continue
                                while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break }
                                val body = """{"protocolVersion":1,"minProtocolVersion":1,"serverId":"s","serverName":"Relay PC","serverVersion":"0.1.0","paired":true,"deviceId":"d","epoch":"e"}"""
                                val bytes = body.toByteArray()
                                ssl.outputStream.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
                                ssl.outputStream.flush()
                            }
                            ssl.close()
                        }
                    }
                }
            }
        }
    }

    private fun api(url: String, tok: String = token): RemoteApi {
        val cfg = RelayConfig(url, tok, relayKey)
        val client = TlsClientFactory.create(identity, fp, base = relayBaseClient(OkHttpClient(), cfg, fp), connectTimeoutMs = 15_000, readTimeoutMs = 15_000)
        return RemoteApi(client, Endpoint(RELAY_HOST, 443))
    }

    @Test
    fun phoneReachesTheDesktopThroughTheRealRelay() = runBlocking {
        val tcp = URI(urls.first { it.startsWith("tcp://") })
        startDesktop(tcp.host, tcp.port)
        assertEquals("Relay PC", api(tcp.toString()).hello().serverName)
        urls.firstOrNull { it.startsWith("ws://") || it.startsWith("wss://") }?.let { ws ->
            assertEquals("Relay PC", api(ws).hello().serverName)
        }
        // A wrong token is rejected by the (fake) desktop through the relay.
        try {
            api(tcp.toString(), tok = "wrong").hello()
            throw AssertionError("must be rejected")
        } catch (e: IOException) {
            assertEquals("rejected", e.causeChain().filterIsInstance<RelayException>().firstOrNull()?.code)
        }
    }
}
