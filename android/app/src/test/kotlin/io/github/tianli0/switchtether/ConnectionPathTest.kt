package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.relay.RELAY_HOST
import io.github.tianli0.switchtether.remote.relay.RelayProtocol
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import java.net.ServerSocket
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain JVM test of the connection order: stored hosts → LAN discovery → relay, against a real
 * TLS 1.3 mutual-auth fake desktop. The relay here is an in-process fake speaking RELAY.md §2.3.
 */
class ConnectionPathTest {
    private val serverCert = HeldCertificate.Builder().ecdsa256().build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().build()
    private val identity = object : ClientIdentity {
        override val alias = "id"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private val fp = Fingerprints.sha256Hex(serverCert.certificate.encoded)
    private lateinit var desktop: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeables = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
        desktop = MockWebServer()
        desktop.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(clientHeld.certificate).build().sslSocketFactory())
        desktop.requireClientAuth()
        desktop.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/v1/hello" -> json("""{"protocolVersion":1,"minProtocolVersion":1,"serverId":"srv","serverName":"PC","serverVersion":"0.1.0","paired":true,"deviceId":"dev","epoch":"e1"}""")
                "/v1/status" -> json("""{"epoch":"e1","headSeq":0}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }
        desktop.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        closeables.forEach { runCatching { it.close() } }
        desktop.close()
    }

    private fun json(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

    /** A port nothing listens on (connection refused quickly). */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    private class MemoryStore(initial: PairedDesktop) : PairingStore {
        override val desktop = MutableStateFlow<PairedDesktop?>(initial)
        override suspend fun current() = desktop.value
        override suspend fun save(desktop: PairedDesktop) { this.desktop.value = desktop }
        override suspend fun clear() { desktop.value = null }
    }

    private fun paired(port: Int, relayUrl: String? = null) = PairedDesktop(
        serverId = "srv", name = "PC", fingerprint = fp, hosts = listOf("127.0.0.1"), lastGoodHost = null, port = port,
        deviceId = "dev", identityAlias = "id", stepUpAlias = null, stepUpEnabled = false, pairedAtMs = 0,
        relayUrl = relayUrl, relaySecret = if (relayUrl != null) "sealed" else null,
    )

    private val relayKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })

    /** Stands in for the Keystore box: "sealed" opens to token "tok" + [relayKey]. */
    private val secrets = object : io.github.tianli0.switchtether.connection.RelaySecrets {
        override fun seal(secret: io.github.tianli0.switchtether.connection.RelaySecret) = "sealed"
        override fun open(desktop: PairedDesktop) =
            if (desktop.relaySecret == "sealed") io.github.tianli0.switchtether.connection.RelaySecret("tok", relayKey) else null
    }

    private fun manager(store: PairingStore, discover: (suspend (String, Long) -> List<Endpoint>)?) = ConnectionManager(
        scope = scope,
        pairingStore = store,
        identityLoader = { identity },
        onRevoked = { _, _ -> },
        baseClient = OkHttpClient(),
        relaySecrets = secrets,
        discover = discover,
    )

    private fun online(m: ConnectionManager): ConnectionState.Online = runBlocking {
        withTimeout(20_000) { m.state.first { it is ConnectionState.Online } as ConnectionState.Online }
    }

    @Test
    fun lanDiscoveryIsUsedWhenStoredHostsFailAndIsRemembered() {
        val store = MemoryStore(paired(deadPort()))
        var askedFor: String? = null
        val m = manager(store) { fingerprint, _ ->
            askedFor = fingerprint
            listOf(Endpoint(desktop.hostName, desktop.port))
        }
        m.start()
        val s = online(m)
        m.stop()
        assertEquals(fp, askedFor)
        assertEquals(desktop.port, s.endpoint.port)
        val saved = store.desktop.value!!
        assertEquals(desktop.hostName, saved.lastGoodHost)
        assertEquals(desktop.port, saved.port)
        assertEquals(desktop.hostName, saved.hosts.first())
    }

    @Test
    fun relayIsTheLastResortAndNeverStoredAsAHost() {
        // Fake raw-TCP relay that splices accepted phones to the fake desktop.
        val relay = ServerSocket(0)
        closeables += relay
        var connectLine: String? = null
        thread(isDaemon = true) {
            while (!relay.isClosed) {
                val phone = runCatching { relay.accept() }.getOrNull() ?: break
                closeables += phone
                thread(isDaemon = true) {
                    connectLine = RelayProtocol.readLine(phone.getInputStream())
                    phone.getOutputStream().apply { write("CHALLENGE n0nce\n".toByteArray()); flush() }
                    val key = RelayProtocol.readLine(phone.getInputStream())
                    val ok = key == "KEY " + RelayProtocol.mac(java.util.Base64.getUrlDecoder().decode(relayKey), "connect", "n0nce", fp)
                    phone.getOutputStream().apply { write((if (ok) "OK\n" else "ERR auth_failed\n").toByteArray()); flush() }
                    if (!ok) return@thread
                    val d = Socket(desktop.hostName, desktop.port)
                    closeables += d
                    thread(isDaemon = true) { runCatching { phone.getInputStream().copyTo(d.getOutputStream()) } }
                    runCatching { d.getInputStream().copyTo(phone.getOutputStream()) }
                }
            }
        }
        val store = MemoryStore(paired(deadPort(), relayUrl = "tcp://127.0.0.1:${relay.localPort}"))
        val m = manager(store) { _, _ -> emptyList() }
        m.start()
        val s = online(m)
        m.stop()
        assertEquals(RELAY_HOST, s.endpoint.host)
        assertEquals("ST-RELAY/1 CONNECT $fp tok", connectLine)
        assertTrue(store.desktop.value!!.hosts == listOf("127.0.0.1"))
        assertEquals(null, store.desktop.value!!.lastGoodHost)
    }
}
