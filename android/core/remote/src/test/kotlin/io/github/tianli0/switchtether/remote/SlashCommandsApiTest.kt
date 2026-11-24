package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.session.ClaudeTools
import io.github.tianli0.switchtether.remote.session.SlashCommand
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** `POST /v1/sessions/{id}/claude/commands`: the slash commands of a running Claude Code session. */
class SlashCommandsApiTest {
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

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun postsToTheCommandsActionAndReadsTheList() = runBlocking {
        server.enqueue(
            json(
                """{"commands":[
                    {"name":"compact","description":"Summarise the conversation","argumentHint":"[instructions]"},
                    {"name":"/review","description":null},
                    {"name":"deploy","argumentHint":"<env>","somethingNew":1},
                    {"name":"  "},{"description":"no name"},"junk",
                    {"name":"COMPACT","description":"a repeat"}
                ],"source":"agent"}""",
            ),
        )
        val list = api().claudeCommands("managed:1", "cmd-1")
        assertEquals(
            listOf(
                SlashCommand("compact", "Summarise the conversation", "[instructions]"),
                SlashCommand("review", "", ""),
                SlashCommand("deploy", "", "<env>"),
            ),
            list,
        )
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/v1/sessions/managed:1/claude/commands", rec.url.encodedPath)
        assertEquals("cmd-1", rec.headers["Idempotency-Key"])
        // No parameters: the body is `{}` plus the command id every side-effecting call carries.
        assertEquals("""{"commandId":"cmd-1"}""", rec.body!!.utf8())
    }

    @Test
    fun noneFromTheAgentIsAnEmptyList() = runBlocking {
        server.enqueue(json("""{"commands":[],"source":"none"}"""))
        assertTrue(api().claudeCommands("managed:1").isEmpty())
        server.enqueue(json("""{"source":"none"}"""))
        assertTrue(api().claudeCommands("managed:1").isEmpty())
    }

    @Test
    fun anOlderDesktopAnswersWithAnError() = runBlocking {
        // The repository turns this into "no commands"; the call itself reports it like any other.
        for (code in listOf(404, 422)) {
            server.enqueue(json("""{"error":{"code":"unknown_action","message":"unknown Claude tool: commands"}}""", code))
            try {
                api().claudeCommands("managed:1")
                fail("expected an ApiException for $code")
            } catch (e: ApiException) {
                assertEquals(code, e.httpStatus)
            }
        }
    }

    /** The shared fixture (also asserted by the Rust test): `aliases` is optional and kept. */
    @Test
    fun theFixtureParsesWithItsAliases() {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        val body = java.io.File(dir, "claude_commands.json").readText()
        assertEquals(
            listOf(
                SlashCommand("compact", "Clear conversation history but keep a summary in context", "<optional custom summarization instructions>"),
                SlashCommand("review", "Review a pull request", "", listOf("rv")),
                SlashCommand("my-plugin:deploy"),
            ),
            ClaudeTools.parseCommands(ProtocolJson.parseToJsonElement(body)),
        )
        // Junk aliases, the name itself and repeats are dropped; a leading slash is removed.
        fun parse(s: String) = ClaudeTools.parseCommands(ProtocolJson.parseToJsonElement(s))
        assertEquals(
            listOf(SlashCommand("usage", aliases = listOf("cost", "stats"))),
            parse("""{"commands":[{"name":"usage","aliases":["/cost","stats","COST","usage","two words","",3,null]}]}"""),
        )
        assertEquals(listOf(SlashCommand("x")), parse("""{"commands":[{"name":"x","aliases":"cost"}]}"""))
    }

    @Test
    fun bodiesThatAreNotAListParseToNothing() {
        fun parse(s: String) = ClaudeTools.parseCommands(ProtocolJson.parseToJsonElement(s))
        assertTrue(parse("""{"commands":"compact"}""").isEmpty())
        assertTrue(parse("""[]""").isEmpty())
        assertTrue(parse("""{"commands":[{"name":"two words"}]}""").isEmpty())
        assertTrue(ClaudeTools.parseCommands(null).isEmpty())
        assertEquals(listOf(SlashCommand("init")), parse("""{"commands":[{"name":"init","description":"","argumentHint":null}]}"""))
    }
}
