package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.protocol.GitOps
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.workspace.FilesViewModel
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain JVM test (Robolectric's sandbox breaks JCE for okhttp-tls): the Git page's writes against a
 * real TLS MockWebServer — the review opens on the session folder, stage / commit carry a commandId
 * and update the page from the answer, a refused write shows git's message.
 */
class GitWriteFlowTest {
    private val serverCert = HeldCertificate.Builder().ecdsa256().build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().build()
    private val identity = object : ClientIdentity {
        override val alias = "t"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private lateinit var server: MockWebServer
    private lateinit var vm: FilesViewModel
    private val posts = ConcurrentLinkedQueue<Pair<String, String>>()

    /** POST answers by path; GETs answer the repository as it is. */
    @Volatile private var commitAnswer: MockResponse? = null
    @Volatile private var staged = false

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun status(staged: Boolean) = """{"root":"/r","branch":"main","upstream":"origin/main","ahead":0,"behind":0,"entries":[
        {"path":"src/a.kt","index":"${if (staged) "M" else "."}","worktree":"${if (staged) "." else "M"}","untracked":false,"conflicted":false}]}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(clientHeld.certificate).build().sslSocketFactory())
        server.requireClientAuth()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (request.method == "POST") posts += path to (request.body?.utf8() ?: "")
                return when (path) {
                    "/v1/capabilities" -> json(200, """{"features":["git","git_write"]}""")
                    "/v1/git/status" -> json(200, status(staged))
                    "/v1/git/log" -> json(200, """{"commits":[]}""")
                    "/v1/git/stage" -> {
                        staged = true
                        json(200, """{"status":${status(true)},"output":""}""")
                    }
                    "/v1/git/commit" -> commitAnswer ?: json(200, """{"status":{"root":"/r","branch":"main","ahead":1,"entries":[]},"output":"[main abc] msg","head":"abc"}""")
                    else -> json(404, """{"error":{"code":"not_found","message":"no"}}""")
                }
            }
        }
        server.start()
        val api = RemoteApi(TlsClientFactory.create(identity, Fingerprints.sha256Hex(serverCert.certificate.encoded)), Endpoint(server.hostName, server.port))
        val conn = object : ConnectionController {
            override val state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)
            override val events = MutableSharedFlow<StreamFrame.Event>()
            override val resyncs = MutableSharedFlow<String>()
            override fun apiOrNull() = api
            override fun retryNow() = Unit
        }
        val gate = StepUpGate({ _, _ -> error("not used") }).apply { grantForTest(StepUpScopes.GIT_WRITE, Long.MAX_VALUE) }
        vm = FilesViewModel(conn, gate, { error("no content resolver in JVM test") }) { null }
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun await(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timeout; state=${vm.state.value}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun reviewStageAndCommit() {
        vm.openReview("/r", listOf("/r/src/a.kt"))
        await { vm.state.value.git is Loadable.Ready && vm.state.value.gitWritable }
        assertTrue(vm.state.value.onlyTurn)
        assertEquals(listOf("/r/src/a.kt"), vm.state.value.turnFiles)

        vm.stage(listOf("src/a.kt"))
        await { vm.state.value.gitBusy == null && vm.state.value.gitNotice != null }
        assertTrue(vm.state.value.gitNotice!!.ok)
        val (stagePath, stageBody) = posts.first { it.first == "/v1/git/stage" }
        assertEquals("/v1/git/stage", stagePath)
        assertTrue(stageBody, stageBody.contains("\"paths\":[\"src/a.kt\"]") && stageBody.contains("\"commandId\""))
        assertTrue((vm.state.value.git as Loadable.Ready).value.entries.single().staged)

        // No message: nothing is sent.
        vm.commit()
        assertFalse(posts.any { it.first == "/v1/git/commit" })
        vm.setCommitMessage("fix: a")
        vm.commit()
        await { vm.state.value.gitBusy == null && vm.state.value.gitNotice?.op == GitOps.COMMIT }
        assertTrue(vm.state.value.gitNotice!!.ok)
        assertEquals("", vm.state.value.commitMessage)
        assertTrue(posts.single { it.first == "/v1/git/commit" }.second.contains("\"message\":\"fix: a\""))
    }

    @Test
    fun aRefusedWriteShowsGitsMessageAndKeepsTheDraft() {
        vm.setRepo("/r")
        await { vm.state.value.git is Loadable.Ready }
        commitAnswer = json(400, """{"error":{"code":"bad_request","message":"Author identity unknown"}}""")
        vm.setCommitMessage("msg")
        vm.commit()
        await { vm.state.value.gitNotice != null }
        val n = vm.state.value.gitNotice!!
        assertFalse(n.ok)
        assertEquals("Author identity unknown", n.text)
        assertEquals("msg", vm.state.value.commitMessage)
        vm.clearGitNotice()
        assertNull(vm.state.value.gitNotice)
    }
}
