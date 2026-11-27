package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.workspace.FilesViewModel
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plain JVM test (Robolectric's sandbox breaks JCE for okhttp-tls). File save → 409 conflict → keep-mine-as-copy / overwrite, against a real TLS MockWebServer. */
class ConflictFlowTest {
    private val serverCert = HeldCertificate.Builder().ecdsa256().build()
    private val clientHeld = HeldCertificate.Builder().ecdsa256().build()
    private val identity = object : ClientIdentity {
        override val alias = "t"
        override val privateKey: PrivateKey = clientHeld.keyPair.private
        override val certificate: X509Certificate = clientHeld.certificate
    }
    private lateinit var server: MockWebServer
    private lateinit var vm: FilesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(clientHeld.certificate).build().sslSocketFactory())
        server.requireClientAuth()
        server.start()
        val api = RemoteApi(TlsClientFactory.create(identity, Fingerprints.sha256Hex(serverCert.certificate.encoded)), Endpoint(server.hostName, server.port))
        val conn = object : ConnectionController {
            override val state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired) // no auto-loading
            override val events = MutableSharedFlow<StreamFrame.Event>()
            override val resyncs = MutableSharedFlow<String>()
            override fun apiOrNull() = api
            override fun retryNow() = Unit
        }
        val gate = StepUpGate({ _, _ -> error("not used") }).apply { grantForTest(StepUpScopes.FILES_WRITE, Long.MAX_VALUE) }
        vm = FilesViewModel(conn, gate, { error("no content resolver in JVM test") }) { null }
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun await(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timeout; state=${vm.state.value}" }
            Thread.sleep(20)
        }
    }

    private fun body(): String = server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()

    @Test
    fun leavingSearchPreventsLateResultsFromReplacingTheDirectory() {
        server.enqueue(json(200, """{"path":"D:\\p","entries":[]}"""))
        vm.browse("D:\\p")
        await { vm.state.value.listing is Loadable.Ready }
        server.takeRequest(5, TimeUnit.SECONDS)!!

        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"results":[]}""")
                .bodyDelay(400, TimeUnit.MILLISECONDS)
                .build(),
        )
        vm.setQuery("old")
        vm.search()
        assertEquals("old", server.takeRequest(5, TimeUnit.SECONDS)!!.url.queryParameter("q"))
        vm.clearSearch()
        await { vm.state.value.results == null && vm.state.value.query.isEmpty() }

        server.enqueue(json(200, """{"path":"D:\\p","entries":[]}"""))
        vm.refreshListing()
        await { !vm.state.value.refreshing }
        Thread.sleep(450)
        assertNull(vm.state.value.results)
        assertEquals("D:\\p", vm.currentDir())
    }

    @Test
    fun navigatingDuringRefreshKeepsTheNewDirectory() {
        server.enqueue(json(200, """{"path":"D:\\p","entries":[]}"""))
        vm.browse("D:\\p")
        await { vm.currentDir() == "D:\\p" }
        server.takeRequest(5, TimeUnit.SECONDS)!!

        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"path":"D:\\p","entries":[]}""")
                .bodyDelay(400, TimeUnit.MILLISECONDS)
                .build(),
        )
        vm.refreshListing()
        assertEquals("D:\\p", server.takeRequest(5, TimeUnit.SECONDS)!!.url.queryParameter("path"))

        server.enqueue(json(200, """{"path":"D:\\p\\next","entries":[]}"""))
        vm.browse("D:\\p\\next")
        await { vm.currentDir() == "D:\\p\\next" }
        Thread.sleep(450)
        assertEquals("D:\\p\\next", vm.currentDir())
        assertEquals(false, vm.state.value.refreshing)
    }

    @Test
    fun editingTheFilterReturnsToTheCurrentFolderImmediately() {
        server.enqueue(json(200, """{"path":"D:\\p","entries":[]}"""))
        vm.browse("D:\\p")
        await { vm.state.value.listing is Loadable.Ready }
        server.enqueue(json(200, """{"results":[]}"""))
        vm.setQuery("old")
        vm.search()
        await { vm.state.value.results is Loadable.Ready }

        vm.setQuery("new")

        assertEquals("new", vm.state.value.query)
        assertNull(vm.state.value.results)
        assertEquals("D:\\p", vm.currentDir())
    }

    @Test
    fun creatingBeforeAnyDirectoryIsLoadedReportsAMessageAndSendsNothing() {
        vm.mkdir("x")
        assertNotNull(vm.state.value.message)
        vm.clearMessage()
        vm.newFile("y.txt")
        assertNotNull(vm.state.value.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keepMineAsCopyReopensASensitiveFileWithItsConfirmation() {
        server.enqueue(json(200, """{"path":"D:\\p\\.env","text":"one","sha256":"s1","size":3,"encoding":"utf-8","sensitive":true}"""))
        vm.openPath("D:\\p\\.env", allowSensitive = true)
        await { vm.state.value.file is Loadable.Ready }
        server.takeRequest()
        vm.startEdit()
        vm.setEditText("mine")
        server.enqueue(json(409, """{"error":{"code":"conflict","message":"changed","details":{"currentSha256":"s2","currentText":"desktop","suggestedCopyPath":"D:\\p\\.env (conflict)"}}}"""))
        vm.save()
        await { vm.state.value.conflict != null }
        body()
        server.enqueue(json(200, """{"sha256":"c1"}"""))
        server.enqueue(json(200, """{"path":"D:\\p\\.env","text":"desktop","sha256":"s2","size":7,"sensitive":true}"""))
        vm.keepMineAsCopy()
        await { (vm.state.value.file as? Loadable.Ready)?.value?.sha256 == "s2" }
        body()
        val reread = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(reread.url.toString().contains("allowSensitive=true"))
    }

    @Test
    fun conflictThenKeepMineAsCopyThenOverwrite() {
        server.enqueue(json(200, """{"path":"D:\\p\\a.txt","text":"one","sha256":"s1","size":3,"encoding":"utf-8"}"""))
        vm.openPath("D:\\p\\a.txt")
        await { vm.state.value.file is Loadable.Ready }
        server.takeRequest()
        vm.startEdit()
        vm.setEditText("mine")

        server.enqueue(json(409, """{"error":{"code":"conflict","message":"changed","details":{"currentSha256":"s2","currentText":"desktop","suggestedCopyPath":"D:\\p\\a (conflict).txt"}}}"""))
        vm.save()
        await { vm.state.value.conflict != null }
        val w1 = body()
        assertTrue(w1.contains("\"baseSha256\":\"s1\""))
        val c = vm.state.value.conflict!!
        assertEquals("desktop", c.desktopText)
        assertEquals("D:\\p\\a (conflict).txt", c.suggestedCopyPath)
        assertTrue(c.diff.contains("-desktop") && c.diff.contains("+mine"))

        // Keep mine as copy: createOnly write to the suggested path, original untouched.
        server.enqueue(json(200, """{"sha256":"c1"}"""))
        server.enqueue(json(200, """{"path":"D:\\p\\a.txt","text":"desktop","sha256":"s2","size":7}"""))
        vm.keepMineAsCopy()
        await { vm.state.value.conflict == null && (vm.state.value.file as? Loadable.Ready)?.value?.sha256 == "s2" }
        val w2 = body()
        assertTrue(w2.contains("\"createOnly\":true"))
        assertTrue(w2.contains("a (conflict).txt"))
        assertTrue(!w2.contains("baseSha256\":\"s"))
        server.takeRequest() // re-read

        // Provoke again and overwrite: re-reads the current sha, then writes against it.
        vm.startEdit()
        vm.setEditText("mine2")
        server.enqueue(json(409, """{"error":{"code":"conflict","message":"changed","details":{"currentSha256":"s3","currentText":"desktop2"}}}"""))
        vm.save()
        await { vm.state.value.conflict != null }
        body()
        assertNotNull(vm.state.value.conflict!!.suggestedCopyPath) // fallback name when the server gives none
        server.enqueue(json(200, """{"path":"D:\\p\\a.txt","text":"desktop2","sha256":"s3","size":8}"""))
        server.enqueue(json(200, """{"sha256":"s4"}"""))
        vm.overwrite()
        await { (vm.state.value.file as? Loadable.Ready)?.value?.sha256 == "s4" }
        server.takeRequest() // read
        val w3 = body()
        assertTrue(w3.contains("\"baseSha256\":\"s3\""))
        assertTrue(w3.contains("\"text\":\"mine2\""))
        assertNull(vm.state.value.conflict)
    }
}
