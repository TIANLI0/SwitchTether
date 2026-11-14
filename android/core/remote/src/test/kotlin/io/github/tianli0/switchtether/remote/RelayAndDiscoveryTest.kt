package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.discovery.DiscoveryTxt
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.pairing.PairingLinkException
import io.github.tianli0.switchtether.remote.pairing.causeChain
import io.github.tianli0.switchtether.remote.relay.RELAY_HOST
import io.github.tianli0.switchtether.remote.relay.RelayConfig
import io.github.tianli0.switchtether.remote.relay.RelayException
import io.github.tianli0.switchtether.remote.relay.RelayProtocol
import io.github.tianli0.switchtether.remote.relay.relayBaseClient
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.PinMismatchException
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Relay client (docs/RELAY.md) against an in-process fake relay that pipes to a fake desktop
 * (MockWebServer with TLS 1.3 + required client auth). Proves the inner mTLS + pin run end to
 * end through both transports, and covers the TXT matching used by LAN discovery.
 */
class RelayAndDiscoveryTest {
    private val serverCert = HeldCertificate.Builder().ecdsa256().commonName("switchtether-desktop").build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().commonName("phone").build()
    private val identity = object : ClientIdentity {
        override val alias = "test-identity"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private val fp = Fingerprints.sha256Hex(serverCert.certificate.encoded)
    private val token = "tok_123-ABC"
    /** Relay key (32 bytes, base64url): bytes 0..31. */
    private val relayKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val nonce = "bm9uY2Utbm9uY2Utbm9uY2Utbm9uY2Utbm9uY2Utbm8"
    private lateinit var desktop: MockWebServer
    private val closeables = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
        desktop = MockWebServer()
        desktop.useHttps(
            HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(clientHeld.certificate).build().sslSocketFactory(),
        )
        desktop.requireClientAuth()
        desktop.start()
    }

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        desktop.close()
    }

    private val hello = """{"protocolVersion":1,"minProtocolVersion":1,"serverId":"s","serverName":"PC","serverVersion":"0.1.0","paired":true,"deviceId":"d","epoch":"e"}"""

    private fun json(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

    private fun pump(from: InputStream, to: OutputStream) = thread(isDaemon = true) {
        runCatching { from.copyTo(to); to.flush() }
        runCatching { to.close() }
    }

    private fun macOk(keyLine: String) = keyLine == "KEY " + RelayProtocol.mac(RelayConfig.decodeKey(relayKey)!!, "connect", nonce, fp)

    /** Fake raw-TCP relay: CONNECT → CHALLENGE → KEY (MAC checked) → OK / ERR, then splices to the fake desktop. */
    private fun startTcpRelay(): Int {
        val ss = ServerSocket(0)
        closeables += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val phone = runCatching { ss.accept() }.getOrNull() ?: break
                closeables += phone
                thread(isDaemon = true) {
                    val line = RelayProtocol.readLine(phone.getInputStream())
                    val parts = line.split(' ')
                    val out = phone.getOutputStream()
                    if (parts.size == 4 && parts[1] == "CONNECT") {
                        out.write("CHALLENGE $nonce\n".toByteArray())
                        out.flush()
                    }
                    when {
                        parts.size != 4 || parts[0] != "ST-RELAY/1" || parts[1] != "CONNECT" -> { out.write("ERR bad_request\n".toByteArray()); phone.close() }
                        !macOk(RelayProtocol.readLine(phone.getInputStream())) -> { out.write("ERR auth_failed\n".toByteArray()); phone.close() }
                        parts[2] != fp -> { out.write("ERR offline\n".toByteArray()); phone.close() }
                        parts[3] != token -> { out.write("ERR rejected bad token\n".toByteArray()); phone.close() }
                        else -> {
                            out.write("OK\n".toByteArray())
                            out.flush()
                            val d = Socket(desktop.hostName, desktop.port)
                            closeables += d
                            pump(phone.getInputStream(), d.getOutputStream())
                            pump(d.getInputStream(), phone.getOutputStream())
                        }
                    }
                }
            }
        }
        return ss.localPort
    }

    /** Fake WebSocket relay (`ws://…/v1/tunnel`): binary messages carry the stream. */
    private fun startWsRelay(): MockWebServer {
        val relay = MockWebServer()
        closeables += relay
        repeat(4) {
            relay.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    var desktopSocket: Socket? = null
                    var handshake = ByteArray(0)
                    var connect: List<String>? = null

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val d = desktopSocket
                        if (d != null) {
                            d.getOutputStream().write(bytes.toByteArray())
                            d.getOutputStream().flush()
                            return
                        }
                        handshake += bytes.toByteArray()
                        val nl = handshake.indexOf('\n'.code.toByte())
                        if (nl < 0) return
                        val line = String(handshake, 0, nl, Charsets.US_ASCII)
                        handshake = handshake.copyOfRange(nl + 1, handshake.size)
                        val first = connect
                        if (first == null) {
                            connect = line.split(' ')
                            webSocket.send("CHALLENGE $nonce\n".toByteArray().toByteString())
                            return
                        }
                        val parts = first
                        if (!macOk(line)) {
                            webSocket.send("ERR auth_failed\n".toByteArray().toByteString())
                            webSocket.close(1000, null)
                            return
                        }
                        if (parts.getOrNull(2) != fp || parts.getOrNull(3) != token) {
                            webSocket.send("ERR rejected\n".toByteArray().toByteString())
                            webSocket.close(1000, null)
                            return
                        }
                        webSocket.send("OK\n".toByteArray().toByteString())
                        val sock = Socket(desktop.hostName, desktop.port)
                        closeables += sock
                        desktopSocket = sock
                        thread(isDaemon = true) {
                            val buf = ByteArray(16 * 1024)
                            val input = sock.getInputStream()
                            while (true) {
                                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                                if (n < 0) break
                                webSocket.send(buf.toByteString(0, n))
                            }
                            webSocket.close(1000, null)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        runCatching { desktopSocket?.close() }
                    }
                }).build(),
            )
        }
        relay.start()
        return relay
    }

    private fun relayApi(config: RelayConfig, pin: String = fp): RemoteApi {
        val base = relayBaseClient(OkHttpClient(), config, fp)
        val client = TlsClientFactory.create(identity, pin, base = base, readTimeoutMs = 10_000)
        return RemoteApi(client, Endpoint(RELAY_HOST, 443))
    }

    @Test
    fun tcpRelayCarriesThePinnedMutualTlsSession() = runBlocking {
        val port = startTcpRelay()
        desktop.enqueue(json(hello))
        val h = relayApi(RelayConfig("tcp://127.0.0.1:$port", token, relayKey)).hello()
        assertEquals("PC", h.serverName)
        val rec = desktop.takeRequest()
        assertEquals(TlsVersion.TLS_1_3, rec.handshake!!.tlsVersion)
        val peer = rec.handshake!!.peerCertificates.single() as X509Certificate
        assertEquals(identity.fingerprint, Fingerprints.sha256Hex(peer.encoded)) // client cert end to end
    }

    @Test
    fun pinIsStillCheckedThroughTheRelay() = runBlocking {
        val port = startTcpRelay()
        desktop.enqueue(json(hello))
        try {
            relayApi(RelayConfig("tcp://127.0.0.1:$port", token, relayKey), pin = "11".repeat(32)).hello()
            fail("a wrong pin must fail through the relay too")
        } catch (e: IOException) {
            assertTrue(e.causeChain().any { it is PinMismatchException })
        }
    }

    @Test
    fun relayErrorsSurfaceAsRelayException() = runBlocking {
        val port = startTcpRelay()
        try {
            relayApi(RelayConfig("tcp://127.0.0.1:$port", "wrong", relayKey)).hello()
            fail("wrong token must be refused")
        } catch (e: IOException) {
            val re = e.causeChain().filterIsInstance<RelayException>().firstOrNull()
            assertEquals("rejected", re?.code)
        }
    }

    @Test
    fun webSocketRelayCarriesThePinnedMutualTlsSession() = runBlocking {
        val relay = startWsRelay()
        desktop.enqueue(json(hello))
        desktop.enqueue(json("""{"ok":true}"""))
        val api = relayApi(RelayConfig("ws://${relay.hostName}:${relay.port}/v1/tunnel", token, relayKey))
        assertEquals("PC", api.hello().serverName)
        val rec = desktop.takeRequest()
        assertEquals(TlsVersion.TLS_1_3, rec.handshake!!.tlsVersion)
        assertEquals("/v1/tunnel", relay.takeRequest().url.encodedPath)
    }

    @Test
    fun pairingLinkCarriesTheRelay() {
        val base = "switchtether://pair?v=1&h=192.168.1.20&p=47615&fp=$fp&id=pair1&s=${"A".repeat(43)}&n=PC"
        val withRelay = PairingLink.parse("$base&r=wss%3A%2F%2Frelay.example.com%2Fv1%2Ftunnel&rt=$token")
        assertEquals("wss://relay.example.com/v1/tunnel", withRelay.relayUrl)
        assertEquals(token, withRelay.relayToken)
        assertTrue(!withRelay.toString().contains(token))
        assertNull(PairingLink.parse(base).relayUrl)
        try {
            PairingLink.parse("$base&r=http%3A%2F%2Fevil")
            fail("only tcp/ws/wss relays")
        } catch (e: PairingLinkException) {
            assertEquals("r", e.field)
        }
        val tcp = RelayConfig("tcp://relay.example.com", token, relayKey)
        assertEquals(47700, tcp.port)
        assertEquals("https://relay.example.com/v1/tunnel", RelayConfig("wss://relay.example.com/v1/tunnel", null, relayKey).webSocketUrl)
    }

    @Test
    fun discoveryTxtMatchesOnlyThePairedFingerprint() {
        fun txt(vararg kv: Pair<String, String>) = kv.associate { it.first to it.second.toByteArray() }
        val good = DiscoveryTxt.parse("SwitchTether-PC", "192.168.1.31", 47615, txt("v" to "1", "fp" to fp.uppercase(), "id" to "srv", "n" to "My PC"))!!
        assertEquals(fp, good.fingerprint)
        assertEquals("My PC", good.name)
        assertNull(DiscoveryTxt.parse("x", "192.168.1.31", 47615, txt("v" to "2", "fp" to fp)))
        assertNull(DiscoveryTxt.parse("x", "192.168.1.31", 47615, txt("v" to "1", "fp" to "abc")))
        assertNull(DiscoveryTxt.parse("x", null, 47615, txt("v" to "1", "fp" to fp)))
        val other = good.copy(fingerprint = "22".repeat(32), host = "192.168.1.40")
        val v6 = good.copy(host = "fe80::1")
        val matches = DiscoveryTxt.matching(listOf(v6, other, good, good), fp)
        assertEquals(listOf("192.168.1.31", "fe80::1"), matches.map { it.host })
        assertEquals(listOf("192.168.1.31", "10.0.0.2", "10.0.0.3"), DiscoveryTxt.mergeHosts(listOf("10.0.0.2", "192.168.1.31", "10.0.0.3"), "192.168.1.31"))
    }

    @Test
    fun relayKeyMacMatchesTheReferenceAndAWrongKeyIsRefused() = runBlocking {
        // Reference computed independently (Python hmac/sha256) for key = bytes 0..31.
        assertEquals(
            "kvDSMEvKDy_WugbzdTUEP0nE4utWNpklcxc3lDf7hIc",
            RelayProtocol.mac(RelayConfig.decodeKey(relayKey)!!, "connect", "abc", "00".repeat(32)),
        )
        val port = startTcpRelay()
        val wrongKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val e = runCatching { relayApi(RelayConfig("tcp://127.0.0.1:$port", token, wrongKey)).hello() }.exceptionOrNull()
        assertTrue("auth_failed expected, got $e", generateSequence(e) { it.cause }.any { it is io.github.tianli0.switchtether.remote.relay.RelayException && it.code == "auth_failed" })
        // No relay key → the relay is not usable at all.
        assertEquals(null, RelayConfig.parseOrNull("tcp://127.0.0.1:$port", token, null))
        assertEquals(null, RelayConfig.parseOrNull("tcp://127.0.0.1:$port", token, "too-short"))
    }

    @Test
    fun pairingLinkCarriesTheRelayKey() {
        val base = "switchtether://pair?v=1&h=10.0.0.2&p=47615&fp=$fp&id=p1&s=${"A".repeat(43)}&r=tcp%3A%2F%2Frelay.example.com&rt=$token"
        val l = io.github.tianli0.switchtether.remote.pairing.PairingLink.parse("$base&rk=$relayKey")
        assertEquals(relayKey, l.relayKey)
        assertTrue("key never printed", !l.toString().contains(relayKey))
        assertEquals(null, io.github.tianli0.switchtether.remote.pairing.PairingLink.parse(base).relayKey)
        assertEquals(null, io.github.tianli0.switchtether.remote.pairing.PairingLink.parseOrNull("$base&rk=short"))
    }
}
