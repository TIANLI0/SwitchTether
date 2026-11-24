package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.protocol.CreateTerminalBody
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.ScrollbackPage
import io.github.tianli0.switchtether.remote.protocol.StepUpGrant
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.protocol.TermLine
import io.github.tianli0.switchtether.remote.protocol.TermRun
import io.github.tianli0.switchtether.remote.protocol.TerminalInfo
import io.github.tianli0.switchtether.remote.protocol.TerminalList
import io.github.tianli0.switchtether.remote.terminal.TermKey
import io.github.tianli0.switchtether.ui.workspace.TermLink
import io.github.tianli0.switchtether.ui.workspace.TerminalApi
import io.github.tianli0.switchtether.ui.workspace.TerminalViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One terminal stream as the view model sees it; the test plays the desktop through [listener]. */
class FakeTermSocket(val terminalId: String, val listener: WebSocketListener) : WebSocket {
    private val request = Request.Builder().url("http://127.0.0.1/v1/terminals/$terminalId/stream").build()
    val sent = mutableListOf<String>()
    var cancelled = false
    var closed = false

    /** `data` of every input message sent so far. */
    val inputs: List<String>
        get() = sent.map { ProtocolJson.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "input" }
            .map { it.getValue("data").jsonPrimitive.content }

    override fun request() = request
    override fun queueSize() = 0L
    override fun send(text: String): Boolean {
        sent += text
        return true
    }

    override fun send(bytes: ByteString) = true
    override fun close(code: Int, reason: String?): Boolean {
        closed = true
        return true
    }

    override fun cancel() {
        cancelled = true
    }

    fun response(code: Int = 101): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("").build()

    fun open() = listener.onOpen(this, response())
    fun closing(code: Int) = listener.onClosing(this, code, "")
    fun fail(t: Throwable = IOException("Socket closed")) = listener.onFailure(this, t, null)
}

/** The desktop's terminal endpoints, in memory. */
class FakeTerminalApi : TerminalApi {
    var list = listOf(TerminalInfo(id = "A"), TerminalInfo(id = "B"))
    val sockets = mutableListOf<FakeTermSocket>()
    val killed = mutableListOf<String>()

    /** When set, `killTerminal` waits for it (a request still in flight). */
    var killGate: CompletableDeferred<Unit>? = null

    /** When set, a scrollback page (not the 1-line "how many are there" probe) waits for it. */
    var pageGate: CompletableDeferred<Unit>? = null
    var scrollLines = List(300) { TermLine(listOf(TermRun(t = "line $it"))) }

    override suspend fun terminals() = TerminalList(terminals = list)
    override suspend fun createTerminal(body: CreateTerminalBody) = CommandResponse(JsonObject(emptyMap()), false)
    override suspend fun killTerminal(id: String) {
        killGate?.await()
        killed += id
        list = list.filter { it.id != id }
    }

    override suspend fun terminalScrollback(id: String, offset: Int, limit: Int): ScrollbackPage {
        if (limit > 1) pageGate?.await()
        return ScrollbackPage(scrollLines.drop(offset).take(limit), scrollLines.size)
    }

    override fun openTerminal(id: String, listener: WebSocketListener): WebSocket = FakeTermSocket(id, listener).also { sockets += it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeTerminalApi()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun grantedGate() = StepUpGate({ _, _ -> error("not used") }).apply { grantForTest(StepUpScopes.TERMINAL, Long.MAX_VALUE) }

    private fun vm(gate: StepUpGate = grantedGate(), conn: FakeConnection = FakeConnection()) =
        TerminalViewModel(conn, gate, { true }, terminalApi = { api })

    /** A gate with no grant: every prompt it raises is collected, for the test to answer. */
    private fun TestScope.promptingGate(): Pair<StepUpGate, List<StepUpGate.Request>> {
        val gate = StepUpGate({ _, _ -> error("not used") })
        val prompts = mutableListOf<StepUpGate.Request>()
        backgroundScope.launch { gate.requests.collect { prompts += it } }
        return gate to prompts
    }

    private fun StepUpGate.approve(req: StepUpGate.Request) {
        grantForTest(StepUpScopes.TERMINAL, Long.MAX_VALUE)
        req.result.complete(Result.success(StepUpGrant(StepUpScopes.TERMINAL)))
    }

    // ---- the four races fixed by the earlier review -------------------------------------------------

    @Test
    fun connectWaitingOnStepUpOpensNothingAfterAnotherTerminalWasSelected() = runTest(dispatcher) {
        val (gate, prompts) = promptingGate()
        val vm = vm(gate)
        vm.select("A")
        assertEquals(1, prompts.size)
        assertEquals(TermLink.CONNECTING, vm.state.value.link)
        assertTrue(api.sockets.isEmpty())

        vm.select("B") // while the prompt for A is still up
        gate.approve(prompts.single())

        assertEquals(listOf("B"), api.sockets.map { it.terminalId })
        assertEquals("B", vm.state.value.selectedId)
        api.sockets.single().open()
        assertEquals(TermLink.OPEN, vm.state.value.link)
    }

    @Test
    fun connectWaitingOnStepUpOpensNothingAfterDisconnect() = runTest(dispatcher) {
        val (gate, prompts) = promptingGate()
        val vm = vm(gate)
        vm.select("A")
        vm.disconnect()
        gate.approve(prompts.single())
        assertTrue(api.sockets.isEmpty())
        assertEquals(TermLink.IDLE, vm.state.value.link)
    }

    @Test
    fun keysQueuedForOneTerminalAreNotTypedIntoAnother() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        vm.typeText("rm -rf build\n") // A's stream is still connecting: queued
        vm.select("B")
        val (a, b) = api.sockets
        b.open()
        a.open() // late open of the superseded stream
        assertEquals(emptyList<String>(), b.inputs)
        assertEquals(emptyList<String>(), a.inputs)

        // Same terminal: what was typed while it reconnected is sent once, in order.
        vm.connect()
        vm.typeText("ls")
        vm.key(TermKey.ENTER)
        val again = api.sockets.last()
        again.open()
        assertEquals(listOf("ls\r"), again.inputs)
        again.open()
        assertEquals(listOf("ls\r"), again.inputs)
    }

    @Test
    fun killOfOneTerminalDoesNotDisconnectTheOneSelectedMeanwhile() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        api.sockets.last().open()
        api.killGate = CompletableDeferred()
        vm.kill()
        vm.select("B")
        val b = api.sockets.last()
        b.open()

        api.killGate!!.complete(Unit)

        assertEquals(listOf("A"), api.killed)
        assertEquals("B", vm.state.value.selectedId)
        assertEquals(TermLink.OPEN, vm.state.value.link)
        assertFalse(b.closed)
        assertFalse(b.cancelled)
        assertEquals(listOf("B"), vm.state.value.terminals.map { it.id })
        vm.typeText("x")
        assertEquals(listOf("x"), b.inputs)
    }

    @Test
    fun killOfTheSelectedTerminalClosesItsStream() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        val a = api.sockets.last()
        a.open()
        vm.kill()
        assertNull(vm.state.value.selectedId)
        assertEquals(TermLink.IDLE, vm.state.value.link)
        assertTrue(a.closed)
    }

    @Test
    fun doubleLoadEarlierOutputPrependsThePageOnce() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        api.pageGate = CompletableDeferred()
        vm.loadScrollback()
        vm.loadScrollback() // double tap: both requests are in flight
        api.pageGate!!.complete(Unit)

        assertEquals(api.scrollLines.subList(100, 300), vm.state.value.scrollback)
        assertFalse(vm.state.value.scrollbackDone)

        // The next tap continues above what is shown.
        vm.loadScrollback()
        assertEquals(api.scrollLines, vm.state.value.scrollback)
        assertTrue(vm.state.value.scrollbackDone)
    }

    @Test
    fun scrollbackPageOfThePreviousTerminalIsDropped() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        api.pageGate = CompletableDeferred()
        vm.loadScrollback()
        vm.select("B")
        api.pageGate!!.complete(Unit)
        assertTrue(vm.state.value.scrollback.isEmpty())
    }

    // ---- input ---------------------------------------------------------------------------------------

    @Test
    fun ctrlLatchSurvivesUntilAKeyIsReallySent() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        val a = api.sockets.last()
        a.open()
        a.closing(1000) // link closed: nothing can be sent
        assertEquals(TermLink.CLOSED, vm.state.value.link)

        vm.toggleCtrl()
        vm.typeText("c")
        vm.key(TermKey.UP)
        assertTrue("the latch must not be spent on a key that went nowhere", vm.state.value.ctrl)

        vm.connect()
        val again = api.sockets.last()
        again.open()
        vm.typeText("") // an edit that produced no text
        assertTrue(vm.state.value.ctrl)
        vm.typeText("c")
        assertEquals(listOf("\u0003"), again.inputs)
        assertFalse(vm.state.value.ctrl)
    }

    @Test
    fun altLatchIsSpentByAKeyQueuedWhileConnecting() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        vm.toggleAlt()
        vm.typeText("b") // queued with the modifier applied
        assertFalse(vm.state.value.alt)
        api.sockets.last().open()
        assertEquals(listOf("\u001bb"), api.sockets.last().inputs)
    }

    @Test
    fun pasteWhileConnectingIsQueuedLikeTypedInput() = runTest(dispatcher) {
        val vm = vm()
        vm.select("A")
        vm.typeText("echo ")
        vm.paste("one\ntwo")
        val a = api.sockets.last()
        assertTrue(a.inputs.isEmpty())
        a.open()
        assertEquals(listOf("echo one\rtwo"), a.inputs)
    }

    // ---- step-up expiry / reconnect -----------------------------------------------------------------

    @Test
    fun lateFailureOfTheSocketCancelledForStepUpKeepsTheStepUpState() = runTest(dispatcher) {
        val gate = grantedGate()
        val vm = vm(gate)
        vm.select("A")
        val a = api.sockets.last()
        a.open()
        a.closing(4401) // the grant expired on the desktop
        assertEquals(TermLink.NEEDS_STEP_UP, vm.state.value.link)
        assertTrue(a.cancelled)
        assertFalse(gate.hasGrant(StepUpScopes.TERMINAL))

        a.fail() // okhttp reports the cancel
        assertEquals(TermLink.NEEDS_STEP_UP, vm.state.value.link)
        assertNull(vm.state.value.error)
    }

    @Test
    fun comingBackOnlineRetriesALinkStuckConnecting() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = vm(conn = conn)
        vm.select("A")
        val first = api.sockets.single() // never opens, never fails: the network went away under it
        assertEquals(TermLink.CONNECTING, vm.state.value.link)
        vm.typeText("ls")

        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "network", null)
        conn.state.value = ONLINE

        assertEquals(2, api.sockets.size)
        assertTrue(first.cancelled)
        val second = api.sockets.last()
        assertEquals("A", second.terminalId)
        second.open()
        assertEquals(TermLink.OPEN, vm.state.value.link)
        assertEquals(listOf("ls"), second.inputs) // what was typed meanwhile is not lost
    }

    @Test
    fun comingBackOnlineReconnectsAClosedLinkButNotALeftOne() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = vm(conn = conn)
        vm.select("A")
        api.sockets.last().open()
        api.sockets.last().fail(IOException("reset"))
        assertEquals(TermLink.CLOSED, vm.state.value.link)
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "network", null)
        conn.state.value = ONLINE
        assertEquals(2, api.sockets.size)

        vm.disconnect() // the page was left
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "network", null)
        conn.state.value = ONLINE
        assertEquals(2, api.sockets.size)
    }

    @Test
    fun comingBackOnlineDoesNotAskForStepUpTwice() = runTest(dispatcher) {
        val (gate, prompts) = promptingGate()
        val conn = FakeConnection()
        val vm = vm(gate, conn)
        vm.select("A") // connecting = the step-up prompt is up
        conn.state.value = ConnectionState.Offline(TEST_DESKTOP, "network", null)
        conn.state.value = ONLINE
        assertEquals(1, prompts.size)

        prompts.single().result.complete(Result.failure(IllegalStateException("cancelled")))
        assertEquals(1, prompts.size)
        assertEquals(TermLink.NEEDS_STEP_UP, vm.state.value.link)
        assertTrue(api.sockets.isEmpty())
    }
}
