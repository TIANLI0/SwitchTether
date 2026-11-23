package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.forward.ForwardFrames
import io.github.tianli0.switchtether.remote.forward.LocalForwarder
import io.github.tianli0.switchtether.remote.forward.TunnelCloseCodes
import io.github.tianli0.switchtether.remote.forward.TunnelConnector
import io.github.tianli0.switchtether.remote.forward.TunnelEvents
import io.github.tianli0.switchtether.remote.forward.TunnelLink
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortForwardTest {
    private fun fixture(name: String): String {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        return File(dir, name).readText()
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun framesMatchTheSharedFixture() {
        val frames = ProtocolJson.parseToJsonElement(fixture("forward_frames.json")).jsonObject["frames"]!!.jsonArray
        for (f in frames) {
            val o = f.jsonObject
            val type = o["type"]!!.jsonPrimitive.int
            val stream = o["stream"]!!.jsonPrimitive.int
            val payload = hex(o["payloadHex"]!!.jsonPrimitive.content)
            val wire = hex(o["hex"]!!.jsonPrimitive.content)
            assertArrayEquals(o["name"].toString(), wire, ForwardFrames.encode(type, stream, payload))
            val d = ForwardFrames.decode(wire)!!
            assertEquals(type, d.type)
            assertEquals(stream, d.stream)
            assertArrayEquals(payload, d.payload)
        }
        assertArrayEquals(hex("060000000500040000"), ForwardFrames.window(5, 262_144))
        val v = ProtocolJson.decodeFromString(ForwardView.serializer(), fixture("forward_view.json"))
        assertEquals(5173, v.port)
        assertEquals(42L, v.totalStreams)
        assertTrue(v.connected)
    }

    // --- A fake desktop: the desktop half of the mux, connecting to a real local service. -------

    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    /** Simulates `GET /v1/forwards/{id}/tunnel` against [targetPort] on this machine. */
    inner class FakeDesktop(private val targetPort: Int) : TunnelConnector {
        val connects = AtomicInteger()
        @Volatile var refuseNext = false
        @Volatile var current: Link? = null

        inner class Link(val events: TunnelEvents) : TunnelLink {
            val sockets = ConcurrentHashMap<Int, Socket>()
            val credit = ConcurrentHashMap<Int, Semaphore>()
            @Volatile var closed = false

            override fun send(frame: ByteArray): Boolean {
                if (closed) return false
                val f = ForwardFrames.decode(frame) ?: return false
                when (f.type) {
                    ForwardFrames.OPEN -> {
                        credit[f.stream] = Semaphore(ForwardFrames.INITIAL_WINDOW)
                        val s = runCatching { Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 2000) } }.getOrNull()
                        if (s == null) {
                            deliver(ForwardFrames.encode(ForwardFrames.RESET, f.stream, "refused".toByteArray()))
                        } else {
                            sockets[f.stream] = s
                            deliver(ForwardFrames.encode(ForwardFrames.OPENED, f.stream))
                            Thread { pump(f.stream, s) }.start()
                        }
                    }
                    ForwardFrames.DATA -> sockets[f.stream]?.let {
                        it.getOutputStream().write(f.payload)
                        deliver(ForwardFrames.window(f.stream, f.payload.size))
                    }
                    ForwardFrames.FIN -> sockets[f.stream]?.let { runCatching { it.shutdownOutput() } }
                    ForwardFrames.RESET -> sockets.remove(f.stream)?.close()
                    ForwardFrames.WINDOW -> credit[f.stream]?.release(ForwardFrames.u32(f.payload)!!.toInt())
                }
                return true
            }

            private fun pump(id: Int, s: Socket) {
                val buf = ByteArray(ForwardFrames.MAX_DATA)
                try {
                    val input = s.getInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) {
                            deliver(ForwardFrames.encode(ForwardFrames.FIN, id))
                            return
                        }
                        credit[id]!!.acquire(n)
                        deliver(ForwardFrames.encode(ForwardFrames.DATA, id, buf, 0, n))
                    }
                } catch (_: IOException) {
                }
            }

            @Synchronized
            fun deliver(frame: ByteArray) {
                if (!closed) events.onFrame(frame)
            }

            /** The desktop (or the network) drops the tunnel. */
            fun drop(code: Int) {
                closed = true
                sockets.values.forEach { runCatching { it.close() } }
                events.onClosed(code, "test")
            }

            override fun close() {
                closed = true
                sockets.values.forEach { runCatching { it.close() } }
            }
        }

        override fun connect(events: TunnelEvents): TunnelLink {
            connects.incrementAndGet()
            if (refuseNext) {
                refuseNext = false
                throw IOException("offline")
            }
            val l = Link(events)
            current = l
            Thread { events.onOpen() }.start()
            return l
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** An HTTP/1.0 server on this machine answering every request with [body]. */
    private fun httpServer(body: (String) -> ByteArray): Int {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        closeables += ss
        Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: return@Thread
                Thread {
                    s.use {
                        val reqLine = it.getInputStream().bufferedReader().readLine() ?: return@Thread
                        val b = body(reqLine)
                        it.getOutputStream().apply {
                            write("HTTP/1.0 200 OK\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray())
                            write(b)
                            flush()
                        }
                    }
                }.start()
            }
        }.start()
        return ss.localPort
    }

    private fun get(localPort: Int, path: String = "/"): ByteArray =
        Socket(InetAddress.getLoopbackAddress(), localPort).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: localhost:$localPort\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().readBytes()
        }

    private fun body(resp: ByteArray): ByteArray {
        val text = String(resp, Charsets.ISO_8859_1)
        val i = text.indexOf("\r\n\r\n")
        return resp.copyOfRange(i + 4, resp.size)
    }

    private fun forwarder(desktop: FakeDesktop, onEnded: (Int) -> Unit = {}): LocalForwarder {
        val f = LocalForwarder(
            localPort = freePort(),
            connector = desktop,
            onEnded = { code, _ -> onEnded(code) },
            errorPage = { kind -> "<h1>$kind</h1>" },
            backoffMs = { 50 },
        )
        closeables += AutoCloseable { f.stop() }
        f.start()
        return f
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    @Test
    fun parallelHttpRequestsAndALargeDownloadArriveIntact() {
        val big = ByteArray(3 * ForwardFrames.INITIAL_WINDOW + 12_345) { (it % 251).toByte() }
        val port = httpServer { line -> if (line.contains("/big")) big else line.toByteArray() }
        val f = forwarder(FakeDesktop(port))
        waitFor("tunnel") { f.status == TunnelStatus.OPEN }
        val results = ConcurrentHashMap<Int, String>()
        val threads = (1..8).map { i -> Thread { results[i] = String(body(get(f.localPort, "/r$i"))) }.apply { start() } }
        threads.forEach { it.join(15_000) }
        for (i in 1..8) assertEquals("GET /r$i HTTP/1.0", results[i])
        assertArrayEquals(big, body(get(f.localPort, "/big")))
        waitFor("streams to finish") { f.stats().activeConnections == 0 }
        assertEquals(9L, f.stats().totalConnections)
        assertTrue(f.stats().bytesDown > big.size)
    }

    @Test
    fun refusedPortAnswersHttpWithAnExplanation() {
        val f = forwarder(FakeDesktop(freePort()))
        waitFor("tunnel") { f.status == TunnelStatus.OPEN }
        val resp = String(get(f.localPort), Charsets.UTF_8)
        assertTrue(resp, resp.startsWith("HTTP/1.1 502"))
        assertTrue(resp, resp.endsWith("<h1>REFUSED</h1>"))
    }

    @Test
    fun aDroppedTunnelReconnectsAndAFinalCloseEndsTheForward() {
        val port = httpServer { "ok".toByteArray() }
        val desktop = FakeDesktop(port)
        val ended = CountDownLatch(1)
        val endCode = AtomicInteger()
        val f = forwarder(desktop) { code ->
            endCode.set(code)
            ended.countDown()
        }
        waitFor("tunnel") { f.status == TunnelStatus.OPEN }
        assertEquals("ok", String(body(get(f.localPort))))
        // Network change: the first reconnect fails (offline), the next one succeeds.
        desktop.refuseNext = true
        desktop.current!!.drop(1006)
        waitFor("reconnect") { f.status == TunnelStatus.OPEN && desktop.connects.get() >= 3 }
        assertEquals("ok", String(body(get(f.localPort))))
        // The desktop stopped the forward: the local port is released.
        desktop.current!!.drop(TunnelCloseCodes.ENDED)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(TunnelCloseCodes.ENDED, endCode.get())
        assertEquals(TunnelStatus.ENDED, f.status)
        waitFor("port released") { runCatching { ServerSocket(f.localPort, 1, InetAddress.getLoopbackAddress()).close() }.isSuccess }
    }

    @Test
    fun aConnectionWhileOfflineGetsTheUnreachablePage() {
        val desktop = object : TunnelConnector {
            override fun connect(events: TunnelEvents): TunnelLink = throw IOException("offline")
        }
        val f = LocalForwarder(freePort(), desktop, errorPage = { "<p>$it</p>" }, connectWaitMs = 300, backoffMs = { 60_000 })
        closeables += AutoCloseable { f.stop() }
        f.start()
        val resp = String(get(f.localPort), Charsets.UTF_8)
        assertTrue(resp, resp.startsWith("HTTP/1.1 503"))
        assertTrue(resp, resp.endsWith("<p>UNREACHABLE</p>"))
    }
}
