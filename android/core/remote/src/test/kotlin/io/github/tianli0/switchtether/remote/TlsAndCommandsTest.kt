package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.command.Commands
import io.github.tianli0.switchtether.remote.pairing.HostRace
import io.github.tianli0.switchtether.remote.pairing.NoReachableHostException
import io.github.tianli0.switchtether.remote.pairing.causeChain
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.PinMismatchException
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import java.io.IOException
import java.net.ServerSocket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Real TLS 1.3 + mutual-auth handshakes against MockWebServer, using software P-256 keys in place
 * of the Android Keystore (same JCA interfaces).
 */
class TlsAndCommandsTest {
    private val serverCert = HeldCertificate.Builder().ecdsa256().commonName("switchtether-desktop").build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().commonName("fake").build()
    private val identity = object : ClientIdentity {
        override val alias = "test-identity"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private val serverFp = Fingerprints.sha256Hex(serverCert.certificate.encoded)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .addTrustedCertificate(clientHeld.certificate) // MockWebServer must accept our self-signed client cert
            .build()
        server.useHttps(serverTls.sslSocketFactory())
        server.requireClientAuth()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api(fp: String = serverFp, readTimeoutMs: Long = 5_000): RemoteApi {
        val client = TlsClientFactory.create(identity, fp, readTimeoutMs = readTimeoutMs)
        return RemoteApi(client, Endpoint(server.hostName, server.port))
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun pinnedMutualTls13Handshake() = runBlocking {
        server.enqueue(json("""{"protocolVersion":1,"minProtocolVersion":1,"serverId":"s","serverName":"PC","serverVersion":"0.1.0","paired":false,"deviceId":null,"epoch":"e"}"""))
        val hello = api().hello()
        assertEquals("PC", hello.serverName)
        val rec = server.takeRequest()
        val hs = rec.handshake!!
        assertEquals(TlsVersion.TLS_1_3, hs.tlsVersion)
        // The server saw exactly our client certificate (identity = its SHA-256).
        val peer = hs.peerCertificates.single() as X509Certificate
        assertEquals(identity.fingerprint, Fingerprints.sha256Hex(peer.encoded))
        assertNotNull(rec.headers["X-Request-Id"])
    }

    @Test
    fun wrongPinIsRejected() = runBlocking {
        server.enqueue(json("{}"))
        val wrong = "00".repeat(32)
        try {
            api(fp = wrong).hello()
            fail("handshake must fail")
        } catch (e: IOException) {
            assertTrue(e.causeChain().any { it is PinMismatchException })
        }
    }

    @Test
    fun clientWithoutTls13IsRefusedByConnectionSpec() {
        assertEquals(listOf(TlsVersion.TLS_1_3), TlsClientFactory.TLS13_ONLY.tlsVersions)
    }

    @Test
    fun apiErrorsAreStructured() = runBlocking {
        var authFailures = 0
        server.enqueue(json("""{"error":{"code":"device_revoked","message":"revoked","retryable":false}}""", 403))
        val a = RemoteApi(TlsClientFactory.create(identity, serverFp), Endpoint(server.hostName, server.port)) { authFailures++ }
        try {
            a.status()
            fail()
        } catch (e: ApiException) {
            assertEquals(ErrorCodes.DEVICE_REVOKED, e.code)
            assertEquals(403, e.httpStatus)
            assertTrue(e.isAuthFailure)
        }
        assertEquals(1, authFailures)
    }

    @Test
    fun commandSuccessCarriesCommandIdAndReplayFlag() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).setHeader("X-Command-Replayed", "true").body("""{"ok":true}""").build())
        val out = Commands.run("cmd-1") { id -> api().interrupt("claude:1", id) }
        assertTrue(out is CommandOutcome.Succeeded)
        assertTrue((out as CommandOutcome.Succeeded).response.replayed)
        val rec = server.takeRequest()
        assertEquals("/v1/sessions/claude:1/interrupt", rec.url.encodedPath)
        assertEquals("cmd-1", rec.headers["Idempotency-Key"])
        assertTrue(rec.body!!.utf8().contains("\"commandId\":\"cmd-1\""))
    }

    @Test
    fun stopPostsToTheSessionWithAnIdempotencyKey() = runBlocking {
        server.enqueue(json("""{"ok":true}""", 200))
        val out = Commands.run("cmd-stop") { id -> api().stop("managed:1", id) }
        assertTrue(out is CommandOutcome.Succeeded)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/v1/sessions/managed:1/stop", rec.url.encodedPath)
        assertEquals("cmd-stop", rec.headers["Idempotency-Key"])
        assertTrue(rec.body!!.utf8().contains("\"commandId\":\"cmd-stop\""))
    }

    @Test
    fun capabilityUnavailableIsDefinitiveRejection() = runBlocking {
        server.enqueue(json("""{"error":{"code":"capability_unavailable","message":"blocked","details":{"status":"BLOCKED","reason":"external session"}}}""", 422))
        val out = Commands.run { id -> api().resolveApproval("s", "r", id, "allow") }
        assertTrue(out is CommandOutcome.Rejected)
        assertEquals("BLOCKED", (out as CommandOutcome.Rejected).error.detailStatus)
    }

    @Test
    fun inProgressIsReported() = runBlocking {
        server.enqueue(json("""{"error":{"code":"command_in_progress","message":"running"}}""", 409))
        assertTrue(Commands.run("c") { id -> api().interrupt("s", id) } is CommandOutcome.InProgress)
    }

    @Test
    fun noResponseAfterSendIsUnknownAndIsNeverRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val out = Commands.run("cmd-x") { id -> api(readTimeoutMs = 700).sendMessage("s", id, "hello") }
        assertTrue("expected Unknown, got $out", out is CommandOutcome.Unknown)
        assertEquals(1, server.requestCount) // exactly one attempt reached the server

        // The client must ask the desktop before offering a retry.
        server.enqueue(json("""{"commandId":"cmd-x","kind":"session.message","status":"succeeded","result":{},"createdAtMs":1,"completedAtMs":2}"""))
        val st = Commands.status("cmd-x") { api().command(it) }
        assertTrue(st is CommandStatus.Succeeded)
        assertNull(Commands.retryCommandId(st, "cmd-x"))
        server.takeRequest(1, TimeUnit.SECONDS) // the stalled message request
        assertEquals("/v1/commands/cmd-x", server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun unknownCommandOnServerAllowsRetryWithSameId() = runBlocking {
        server.enqueue(json("""{"error":{"code":"not_found","message":"unknown commandId"}}""", 404))
        val st = Commands.status("cmd-y") { api().command(it) }
        assertEquals(CommandStatus.NotFound, st)
        assertEquals("cmd-y", Commands.retryCommandId(st, "cmd-y"))

        server.enqueue(json("""{"commandId":"cmd-z","status":"failed","error":{"code":"internal"}}"""))
        val failed = Commands.status("cmd-z") { api().command(it) }
        assertTrue(failed is CommandStatus.Failed)
        val retryId = Commands.retryCommandId(failed, "cmd-z")
        assertNotNull(retryId)
        assertFalse(retryId == "cmd-z")
    }

    @Test
    fun connectionRefusedIsNotSent() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val a = RemoteApi(TlsClientFactory.create(identity, serverFp, connectTimeoutMs = 1_000), Endpoint("127.0.0.1", freePort))
        val out = Commands.run("c") { id -> a.interrupt("s", id) }
        assertTrue("expected NotSent, got $out", out is CommandOutcome.NotSent)
    }

    @Test
    fun hostRacePicksReachableCandidateAndReportsPinMismatch() = runBlocking {
        server.enqueue(json("""{"protocolVersion":1,"serverId":"s","serverName":"PC","epoch":"e"}"""))
        val deadPort = ServerSocket(0).use { it.localPort }
        val endpoints = listOf(Endpoint("127.0.0.1", deadPort), Endpoint(server.hostName, server.port))
        val (winner, hello) = HostRace.first(endpoints, perHostTimeoutMs = 3_000) { ep ->
            RemoteApi(TlsClientFactory.create(identity, serverFp, connectTimeoutMs = 1_000), ep).hello()
        }
        assertEquals(server.port, winner.port)
        assertEquals("PC", hello.serverName)

        server.enqueue(json("{}"))
        try {
            HostRace.first(listOf(Endpoint(server.hostName, server.port))) { ep ->
                RemoteApi(TlsClientFactory.create(identity, "11".repeat(32)), ep).hello()
            }
            fail()
        } catch (e: NoReachableHostException) {
            assertTrue(e.pinMismatch)
        }
    }

    @Test
    fun verificationCodeMatchesDesktopFormula() {
        // Reference value computed with Python: sha256(b"switchtether-sas-v1|aa..|bb..")[:4] big-endian % 1e6
        val code = Fingerprints.verificationCode("a".repeat(64), "b".repeat(64))
        assertEquals(6, code.length)
        assertEquals(EXPECTED_SAS, code)
    }

    @Test
    fun fingerprintFormatting() {
        assertEquals("ab12 cd34 ef56 7890", Fingerprints.short("ab12cd34ef567890" + "0".repeat(48)))
        assertEquals(16 * 4 + 15, Fingerprints.grouped("f".repeat(64)).length)
    }

    companion object {
        const val EXPECTED_SAS = "333724"
    }
}
