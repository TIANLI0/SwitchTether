package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.events.EventStreamClient
import io.github.tianli0.switchtether.remote.events.StreamCursor
import io.github.tianli0.switchtether.remote.events.StreamSignal
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The event stream's own dead-socket detection: a phone that slept or was frozen must not drop a
 * healthy socket on waking, a dead one must still be noticed, and a network change is checked with
 * one ping on the socket itself.
 */
class EventStreamKeepAliveTest {
    private val serverCert = HeldCertificate.Builder().ecdsa256().commonName("switchtether-desktop").build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().commonName("fake").build()
    private val identity = object : ClientIdentity {
        override val alias = "test-identity"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        val tls = HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(clientHeld.certificate).build()
        server.useHttps(tls.sslSocketFactory())
        server.requireClientAuth()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api() = RemoteApi(
        TlsClientFactory.create(identity, Fingerprints.sha256Hex(serverCert.certificate.encoded), readTimeoutMs = 5_000),
        Endpoint(server.hostName, server.port),
    )

    /** A desktop that answers every ping (or never answers anything). */
    private fun desktop(answers: Boolean) = server.enqueue(
        MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (answers && text.contains("\"ping\"")) webSocket.send("""{"type":"pong","ts":null}""")
            }
        }).build(),
    )

    private val clock = AtomicLong(0)
    private fun now(): Long = System.nanoTime() / 1_000_000 + clock.get()

    private fun client() = EventStreamClient(api(), pingIntervalMs = 100, stallTimeoutMs = 400, probeTimeoutMs = 400, nowMs = ::now)

    /** The first failure within [ms], or null; [whileOpen] runs once the socket is open. */
    private fun failureWithin(ms: Long, checks: Channel<Unit>? = null, whileOpen: () -> Unit = {}): StreamSignal.Failure? = runBlocking {
        withTimeoutOrNull(ms) {
            client().connect(StreamCursor.INITIAL, checks)
                .filter { s ->
                    if (s is StreamSignal.Opened) whileOpen()
                    s is StreamSignal.Failure
                }
                .first() as StreamSignal.Failure
        }
    }

    @Test
    fun aLongSleepIsNotTakenForADeadSocket() {
        desktop(answers = true)
        // The clock jumps ten minutes at once (a process frozen in the background): the socket is
        // suspect, the confirming ping is answered, and it stays.
        assertNull(failureWithin(2_000) { clock.addAndGet(600_000) })
    }

    @Test
    fun aSilentSocketIsStillDropped() {
        desktop(answers = false)
        assertNotNull(failureWithin(3_000))
    }

    @Test
    fun aNetworkChangeCheckKeepsAHealthySocket() {
        desktop(answers = true)
        val checks = Channel<Unit>(Channel.CONFLATED)
        assertNull(failureWithin(1_500, checks) { checks.trySend(Unit) })
    }

    @Test
    fun aNetworkChangeCheckDropsADeadSocketWithoutWaitingForTheStallTimeout() {
        desktop(answers = false)
        val checks = Channel<Unit>(Channel.CONFLATED)
        val c = EventStreamClient(api(), pingIntervalMs = 60_000, stallTimeoutMs = 120_000, probeTimeoutMs = 300, nowMs = ::now)
        val failure = runBlocking {
            withTimeoutOrNull(2_000) {
                c.connect(StreamCursor.INITIAL, checks)
                    .filter { s ->
                        if (s is StreamSignal.Opened) checks.trySend(Unit)
                        s is StreamSignal.Failure
                    }
                    .first()
            }
        }
        assertNotNull(failure)
    }
}
