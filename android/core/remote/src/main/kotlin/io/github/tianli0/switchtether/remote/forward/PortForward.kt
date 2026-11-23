package io.github.tianli0.switchtether.remote.forward

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Port forwarding, phone side (docs/PROTOCOL.md §7.11). The phone listens on `127.0.0.1:<local>`;
 * every connection accepted there becomes one stream in the lease's tunnel (a single WebSocket),
 * and the desktop connects it to `localhost:<port>` on the computer.
 *
 * Frame: `[type u8][stream u32 BE][payload]`, one binary WebSocket message each.
 */
object ForwardFrames {
    const val OPEN = 1
    const val OPENED = 2
    const val DATA = 3
    const val FIN = 4
    const val RESET = 5
    const val WINDOW = 6

    /** Bytes either side may send on a stream before the peer returns credit. */
    const val INITIAL_WINDOW = 256 * 1024

    /** Largest DATA payload. */
    const val MAX_DATA = 32 * 1024

    class Frame(val type: Int, val stream: Int, val payload: ByteArray)

    fun encode(type: Int, stream: Int, payload: ByteArray = EMPTY, off: Int = 0, len: Int = payload.size): ByteArray {
        val out = ByteArray(5 + len)
        out[0] = type.toByte()
        out[1] = (stream ushr 24).toByte()
        out[2] = (stream ushr 16).toByte()
        out[3] = (stream ushr 8).toByte()
        out[4] = stream.toByte()
        System.arraycopy(payload, off, out, 5, len)
        return out
    }

    fun window(stream: Int, bytes: Int): ByteArray =
        encode(WINDOW, stream, byteArrayOf((bytes ushr 24).toByte(), (bytes ushr 16).toByte(), (bytes ushr 8).toByte(), bytes.toByte()))

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 5) return null
        val type = bytes[0].toInt() and 0xff
        if (type !in OPEN..WINDOW) return null
        val stream = ((bytes[1].toInt() and 0xff) shl 24) or ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 8) or (bytes[4].toInt() and 0xff)
        return Frame(type, stream, bytes.copyOfRange(5, bytes.size))
    }

    fun u32(payload: ByteArray): Long? = if (payload.size != 4) null else
        ((payload[0].toLong() and 0xff) shl 24) or ((payload[1].toLong() and 0xff) shl 16) or
            ((payload[2].toLong() and 0xff) shl 8) or (payload[3].toLong() and 0xff)

    private val EMPTY = ByteArray(0)
}

/** Close codes of the tunnel WebSocket (and the HTTP status of a refused upgrade). */
object TunnelCloseCodes {
    /** The lease was stopped or expired: start the forward again (needs step-up). */
    const val ENDED = 4410
    /** A newer tunnel for the same lease replaced this one. */
    const val REPLACED = 4409
    const val REVOKED = 4403
    /** Upgrade refused: unknown or expired lease. */
    const val NOT_FOUND = 404

    fun isFinal(code: Int): Boolean = code == ENDED || code == NOT_FOUND || code == REVOKED || code == 401
}

/** Callbacks from one tunnel WebSocket. */
interface TunnelEvents {
    fun onOpen()
    fun onFrame(bytes: ByteArray)

    /** Closed or failed. [code]: WebSocket close code, HTTP status of a refused upgrade, or -1. */
    fun onClosed(code: Int, reason: String?)
}

/** One tunnel WebSocket. [send] is thread-safe and never blocks. */
interface TunnelLink {
    fun send(frame: ByteArray): Boolean
    fun close()
}

/** Opens a tunnel for the lease; throws when the computer is not reachable right now. */
fun interface TunnelConnector {
    fun connect(events: TunnelEvents): TunnelLink
}

enum class TunnelStatus { CONNECTING, OPEN, WAITING, ENDED }

data class ForwarderStats(
    val status: TunnelStatus,
    val activeConnections: Int,
    val totalConnections: Long,
    val bytesDown: Long,
    val bytesUp: Long,
)

/**
 * Listens on [localPort] (loopback only) and carries each accepted connection through the tunnel.
 *
 * - The tunnel is opened at [start] and reopened with backoff after it drops (network change,
 *   relay ↔ direct switch); [reconnectNow] skips the wait. A final close ([TunnelCloseCodes.isFinal])
 *   ends the forwarder and calls [onEnded].
 * - A connection that arrives while the tunnel is down waits up to [connectWaitMs] for it.
 * - When the desktop refuses (nothing listens on the port) or the computer is unreachable, an
 *   HTTP client gets a small explanatory page from [errorPage] instead of an empty reply.
 */
class LocalForwarder(
    val localPort: Int,
    private val connector: TunnelConnector,
    private val onStatus: (TunnelStatus) -> Unit = {},
    private val onEnded: (code: Int, reason: String?) -> Unit = { _, _ -> },
    private val errorPage: (kind: ErrorKind) -> String? = { null },
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val connectWaitMs: Long = 10_000,
    private val backoffMs: (attempt: Int) -> Long = { a -> minOf(15_000L, 500L shl minOf(a, 5)) },
) {
    enum class ErrorKind { REFUSED, UNREACHABLE }

    private val threads = ThreadFactory { r -> Thread(r, "forward-$localPort").apply { isDaemon = true } }
    private val pool: ExecutorService = Executors.newCachedThreadPool(threads)
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threads)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextId = AtomicInteger(1)
    private val lock = Object()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    @Volatile private var link: TunnelLink? = null
    @Volatile private var linkGen = 0
    @Volatile private var openLatch = CountDownLatch(1)
    @Volatile var status: TunnelStatus = TunnelStatus.WAITING
        private set
    private var attempts = 0
    private val total = AtomicLong()
    private val down = AtomicLong()
    private val up = AtomicLong()

    fun stats() = ForwarderStats(status, streams.size, total.get(), down.get(), up.get())

    /** Binds the local port (throws [java.net.BindException] when it is taken) and opens the tunnel. */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, localPort), 64)
        server = s
        pool.execute { acceptLoop(s) }
        connectTunnel()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { server?.close() }
        streams.values.forEach { it.abort() }
        streams.clear()
        link?.close()
        link = null
        setStatus(TunnelStatus.ENDED)
        openLatch.countDown()
        timer.shutdownNow()
        pool.shutdownNow()
    }

    /** Reconnect without waiting for the backoff (the phone just came online). */
    fun reconnectNow() {
        if (stopped) return
        synchronized(lock) {
            if (status == TunnelStatus.WAITING) attempts = 0 else return
        }
        connectTunnel()
    }

    private fun setStatus(s: TunnelStatus) {
        if (status == s) return
        if (status == TunnelStatus.ENDED) return
        status = s
        onStatus(s)
    }

    private fun connectTunnel() {
        val gen: Int
        synchronized(lock) {
            // CONNECTING: an attempt is in flight; OPEN: nothing to do.
            if (stopped || status == TunnelStatus.OPEN || status == TunnelStatus.CONNECTING) return
            gen = ++linkGen
            if (openLatch.count == 0L) openLatch = CountDownLatch(1)
            setStatus(TunnelStatus.CONNECTING)
        }
        val events = object : TunnelEvents {
            override fun onOpen() {
                if (gen != linkGen || stopped) return
                synchronized(lock) { attempts = 0 }
                setStatus(TunnelStatus.OPEN)
                openLatch.countDown()
            }

            override fun onFrame(bytes: ByteArray) {
                if (gen != linkGen || stopped) return
                handleFrame(bytes)
            }

            override fun onClosed(code: Int, reason: String?) {
                if (gen != linkGen || stopped) return
                tunnelLost(code, reason)
            }
        }
        val l = try {
            connector.connect(events)
        } catch (e: Exception) {
            if (gen == linkGen) tunnelLost(-1, e.message)
            return
        }
        synchronized(lock) {
            if (gen == linkGen && !stopped) link = l else l.close()
        }
    }

    private fun tunnelLost(code: Int, reason: String?) {
        val delay: Long
        synchronized(lock) {
            link?.close()
            link = null
            linkGen++
            if (TunnelCloseCodes.isFinal(code)) {
                Thread {
                    stop()
                    onEnded(code, reason)
                }.start()
                return
            }
            setStatus(TunnelStatus.WAITING)
            delay = backoffMs(attempts++)
        }
        // Connections inside a dropped tunnel cannot continue (the desktop closed its ends).
        streams.values.forEach { it.abort() }
        streams.clear()
        runCatching { timer.schedule({ if (status == TunnelStatus.WAITING) connectTunnel() }, delay, TimeUnit.MILLISECONDS) }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!stopped) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                if (stopped) return
                continue
            }
            runCatching { sock.tcpNoDelay = true }
            pool.execute { serve(sock) }
        }
    }

    private fun serve(sock: Socket) {
        if (status != TunnelStatus.OPEN) {
            if (status == TunnelStatus.WAITING) reconnectNow()
            val ok = runCatching { openLatch.await(connectWaitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!ok || status != TunnelStatus.OPEN) {
                Stream(0, sock).failWithPage(ErrorKind.UNREACHABLE)
                return
            }
        }
        val id = nextId.getAndAdd(2).let { if (it <= 0) { nextId.set(3); 1 } else it }
        val stream = Stream(id, sock)
        streams[id] = stream
        total.incrementAndGet()
        if (!send(ForwardFrames.encode(ForwardFrames.OPEN, id))) {
            streams.remove(id)
            stream.failWithPage(ErrorKind.UNREACHABLE)
            return
        }
        pool.execute { stream.writerLoop() }
        stream.readerLoop()
    }

    private fun send(frame: ByteArray): Boolean = link?.send(frame) ?: false

    private fun handleFrame(bytes: ByteArray) {
        val f = ForwardFrames.decode(bytes) ?: return
        val s = streams[f.stream] ?: return
        when (f.type) {
            ForwardFrames.OPENED -> Unit
            ForwardFrames.DATA -> {
                down.addAndGet(f.payload.size.toLong())
                s.incoming.put(f.payload)
            }
            ForwardFrames.FIN -> s.incoming.put(FIN_MARK)
            ForwardFrames.RESET -> {
                streams.remove(f.stream)
                val reason = String(f.payload, Charsets.US_ASCII)
                if (reason == "refused") s.failWithPage(ErrorKind.REFUSED) else s.abort()
            }
            ForwardFrames.WINDOW -> ForwardFrames.u32(f.payload)?.let { s.credit.release(it.toInt().coerceAtLeast(0)) }
        }
    }

    private inner class Stream(val id: Int, val socket: Socket) {
        val credit = Semaphore(ForwardFrames.INITIAL_WINDOW)
        val incoming = LinkedBlockingQueue<ByteArray>()
        @Volatile var sentAny = false
        @Volatile var receivedAny = false
        @Volatile private var head: ByteArray = ByteArray(0)
        @Volatile private var finished = false
        private val halves = AtomicInteger(0)

        /** One direction ended cleanly; after both, the stream is done. */
        fun halfDone() {
            if (halves.incrementAndGet() == 2 && streams.remove(id) != null) {
                finished = true
                runCatching { socket.close() }
            }
        }

        /** Local app → desktop. */
        fun readerLoop() {
            val buf = ByteArray(ForwardFrames.MAX_DATA)
            try {
                val input = socket.getInputStream()
                while (!finished) {
                    val n = input.read(buf)
                    if (n < 0) {
                        send(ForwardFrames.encode(ForwardFrames.FIN, id))
                        halfDone()
                        return
                    }
                    if (n == 0) continue
                    if (!sentAny) head = buf.copyOf(minOf(n, 8))
                    sentAny = true
                    credit.acquire(n)
                    if (finished) return
                    up.addAndGet(n.toLong())
                    if (!send(ForwardFrames.encode(ForwardFrames.DATA, id, buf, 0, n))) {
                        abort()
                        return
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
                if (!finished && streams.remove(id) != null) {
                    send(ForwardFrames.encode(ForwardFrames.RESET, id, "closed".toByteArray()))
                    abort()
                }
            }
        }

        /** Desktop → local app; returns credit after each write. */
        fun writerLoop() {
            try {
                val output = socket.getOutputStream()
                while (!finished) {
                    val chunk = incoming.take()
                    if (chunk === FIN_MARK) {
                        runCatching { socket.shutdownOutput() }
                        halfDone()
                        return
                    }
                    if (chunk === ABORT_MARK) return
                    receivedAny = true
                    output.write(chunk)
                    output.flush()
                    send(ForwardFrames.window(id, chunk.size))
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
                if (!finished && streams.remove(id) != null) {
                    send(ForwardFrames.encode(ForwardFrames.RESET, id, "closed".toByteArray()))
                    abort()
                }
            }
        }

        fun abort() {
            if (finished) return
            finished = true
            incoming.offer(ABORT_MARK)
            credit.release(ForwardFrames.INITIAL_WINDOW)
            runCatching { socket.close() }
        }

        /**
         * Nothing listens on the desktop's port / the computer is unreachable: an HTTP request
         * that got no reply yet is answered with an explanatory page; anything else is closed.
         */
        fun failWithPage(kind: ErrorKind) {
            if (finished) return
            try {
                if (!receivedAny) {
                    if (!sentAny) {
                        // Give the client a moment to send its request line (we read it to tell HTTP apart).
                        socket.soTimeout = 1500
                        val b = ByteArray(8)
                        val n = runCatching { socket.getInputStream().read(b) }.getOrDefault(-1)
                        if (n > 0) head = b.copyOf(n)
                    }
                    if (looksLikeHttp(head)) {
                        errorPage(kind)?.let { html ->
                            val body = html.toByteArray(Charsets.UTF_8)
                            val status = if (kind == ErrorKind.REFUSED) "502 Bad Gateway" else "503 Service Unavailable"
                            val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                            socket.getOutputStream().apply {
                                write(header.toByteArray(Charsets.US_ASCII))
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            } catch (_: SocketException) {
            } catch (_: IOException) {
            } finally {
                abort()
            }
        }
    }

    companion object {
        private val FIN_MARK = ByteArray(0)
        private val ABORT_MARK = ByteArray(0)
        private val METHODS = listOf("GET ", "POST", "HEAD", "PUT ", "DELE", "OPTI", "PATC", "CONN", "TRAC")

        fun looksLikeHttp(head: ByteArray): Boolean {
            if (head.size < 4) return false
            val s = String(head, 0, 4, Charsets.US_ASCII)
            return METHODS.any { s.startsWith(it) }
        }
    }
}
