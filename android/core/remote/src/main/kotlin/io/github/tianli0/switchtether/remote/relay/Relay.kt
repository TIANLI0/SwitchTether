package io.github.tianli0.switchtether.remote.relay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Client side of the SwitchTether relay (docs/RELAY.md, protocol v1). The relay is a byte pipe:
 * the phone opens a relay connection, sends `ST-RELAY/1 CONNECT <fp> <token>`, answers the
 * relay's `CHALLENGE` with `KEY <mac>` (relay key, §2.0), waits for `OK`
 * and then runs the **unchanged** pinned mutual-TLS session to the desktop over it. This file
 * only provides plain sockets; TLS, pinning and the client certificate stay in
 * [io.github.tianli0.switchtether.remote.tls.TlsClientFactory].
 */
/**
 * [key]: the relay key (RELAY.md §2.0), base64url of 32 bytes. Without it the relay cannot be used
 * (it would answer `ERR auth_failed`), so [parseOrNull] returns null.
 */
data class RelayConfig(val url: String, val token: String?, val key: String) {
    enum class Transport { TCP, WEBSOCKET }

    val transport: Transport
    val host: String
    val port: Int

    init {
        val u = URI(url.trim())
        when (u.scheme?.lowercase()) {
            "tcp" -> {
                transport = Transport.TCP
                host = u.host ?: throw IllegalArgumentException("relay url has no host")
                port = if (u.port > 0) u.port else DEFAULT_TCP_PORT
            }
            "ws", "wss" -> {
                transport = Transport.WEBSOCKET
                host = u.host ?: throw IllegalArgumentException("relay url has no host")
                port = if (u.port > 0) u.port else if (u.scheme.equals("wss", true)) 443 else 80
            }
            else -> throw IllegalArgumentException("unsupported relay scheme: ${u.scheme}")
        }
        require(token == null || TOKEN.matches(token)) { "invalid relay token" }
        require(decodeKey(key) != null) { "invalid relay key" }
    }

    /** The decoded 32-byte relay key. */
    val keyBytes: ByteArray get() = decodeKey(key)!!

    /** `ws://` / `wss://` URL as OkHttp accepts it (`http(s)://` with the same path). */
    val webSocketUrl: String
        get() = url.trim().replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")

    override fun toString(): String = "RelayConfig(url=$url, token=${if (token == null) "none" else "<redacted>"}, key=<redacted>)"

    companion object {
        const val DEFAULT_TCP_PORT = 47700
        private val TOKEN = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** null when the URL or the relay key is missing/invalid: the relay is then not tried. */
        fun parseOrNull(url: String?, token: String?, key: String?): RelayConfig? {
            val u = url?.takeIf { it.isNotBlank() } ?: return null
            val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { RelayConfig(u, token?.takeIf { t -> t.isNotBlank() }, k) }.getOrNull()
        }

        /** base64url (padding optional) of exactly 32 bytes, else null. */
        fun decodeKey(key: String?): ByteArray? = runCatching {
            java.util.Base64.getUrlDecoder().decode(key!!.trim().trimEnd('='))
        }.getOrNull()?.takeIf { it.size == 32 }
    }
}

/** `ERR <code> <text>` from the relay (offline, rejected, timeout, busy, …). */
class RelayException(val code: String, message: String) : IOException("relay: $code${if (message.isNotEmpty()) " ($message)" else ""}")

object RelayProtocol {
    const val VERSION_LINE_PREFIX = "ST-RELAY/1"
    private const val MAX_LINE = 8 * 1024

    fun connectLine(fingerprint: String, token: String?): String = "$VERSION_LINE_PREFIX CONNECT $fingerprint ${token ?: "-"}\n"

    /**
     * Relay-key MAC (§2.0): base64url-no-pad(HMAC-SHA256(key, "switchtether-relay-v1 " + role + "\n"
     * + nonce + "\n" + fp)); [nonce] is the text exactly as received, no trailing newline.
     */
    fun mac(key: ByteArray, role: String, nonce: String, fingerprint: String): String {
        val h = javax.crypto.Mac.getInstance("HmacSHA256")
        h.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        val msg = "switchtether-relay-v1 $role\n$nonce\n$fingerprint".toByteArray(Charsets.UTF_8)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h.doFinal(msg))
    }

    /** Reads one `\n`-terminated ASCII line byte by byte (never consumes bytes after the newline). */
    fun readLine(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("relay closed the connection during the handshake")
            if (b == '\n'.code) break
            if (buf.size() >= MAX_LINE) throw IOException("relay handshake line too long")
            buf.write(b)
        }
        return buf.toString(Charsets.US_ASCII).trimEnd('\r')
    }

    /**
     * Phone handshake (§2.3): `CONNECT <fp> <token>` → `CHALLENGE <nonce>` → `KEY <mac>` → `OK`.
     * Throws [RelayException] on `ERR …` (`auth_failed`: wrong relay key).
     */
    fun handshake(input: InputStream, output: OutputStream, fingerprint: String, token: String?, key: ByteArray) {
        output.write(connectLine(fingerprint, token).toByteArray(Charsets.US_ASCII))
        output.flush()
        val challenge = readLine(input)
        failOnErr(challenge)
        val nonce = challenge.removePrefix("CHALLENGE ").takeIf { challenge.startsWith("CHALLENGE ") && it.isNotBlank() && ' ' !in it }
            ?: throw IOException("unexpected relay reply")
        output.write("KEY ${mac(key, "connect", nonce, fingerprint)}\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val line = readLine(input)
        if (line == "OK") return
        failOnErr(line)
        throw IOException("unexpected relay reply")
    }

    private fun failOnErr(line: String) {
        if (line.startsWith("ERR")) {
            val parts = line.split(' ', limit = 3)
            throw RelayException(parts.getOrNull(1) ?: "unknown", parts.getOrNull(2).orEmpty())
        }
    }
}

/** Host name OkHttp sees for relay connections (never resolved: [RelayDns] answers it). */
const val RELAY_HOST = "relay.switchtether.invalid"

/** Resolves [RELAY_HOST] without DNS; the socket factory ignores the address anyway. */
object RelayDns : Dns {
    private val PLACEHOLDER = InetAddress.getByAddress(RELAY_HOST, byteArrayOf(192.toByte(), 0, 2, 1))

    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname == RELAY_HOST) listOf(PLACEHOLDER) else Dns.SYSTEM.lookup(hostname)
}

/** Raw TCP transport: connects to the relay instead of the requested address, then handshakes. */
class RelayTcpSocket(private val config: RelayConfig, private val fingerprint: String) : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        super.connect(InetSocketAddress(config.host, config.port), timeout)
        val previous = soTimeout
        // The relay waits up to 10 s for the desktop's ACCEPT.
        soTimeout = HANDSHAKE_TIMEOUT_MS
        try {
            RelayProtocol.handshake(getInputStream(), getOutputStream(), fingerprint, config.token, config.keyBytes)
        } catch (e: IOException) {
            runCatching { close() }
            throw e
        } finally {
            if (!isClosed) soTimeout = previous
        }
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
    }
}

/**
 * WebSocket transport: a [Socket] whose byte streams are carried in binary WebSocket messages
 * (`wss://host/v1/tunnel`). OkHttp's TLS layer wraps it exactly like a TCP socket.
 */
class WebSocketRelaySocket(
    private val wsClient: OkHttpClient,
    private val config: RelayConfig,
    private val fingerprint: String,
) : Socket() {
    private val incoming = LinkedBlockingQueue<ByteArray>(512)
    private val opened = CountDownLatch(1)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var failure: Throwable? = null
    private val closed = AtomicBoolean(false)
    private val remoteClosed = AtomicBoolean(false)
    @Volatile private var readTimeoutMs = 0
    @Volatile private var connected = false

    private val input = object : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (current == null || pos >= current!!.size) {
                if (closed.get()) throw IOException("socket closed")
                val next = try {
                    if (readTimeoutMs > 0) incoming.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                        ?: throw SocketTimeoutException("relay read timed out")
                    else incoming.take()
                } catch (e: InterruptedException) {
                    throw InterruptedIOException()
                }
                if (next === EOF) {
                    failure?.let { throw IOException("relay websocket failed: ${it.message}", it) }
                    return -1
                }
                current = next
                pos = 0
            }
            val c = current!!
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = (current?.size ?: 0) - pos

        override fun close() = this@WebSocketRelaySocket.close()
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get() || remoteClosed.get()) throw IOException("socket closed")
            val socket = ws ?: throw IOException("not connected")
            var o = off
            var remaining = len
            while (remaining > 0) {
                val n = minOf(remaining, MAX_MESSAGE)
                if (!socket.send(b.toByteString(o, n))) throw IOException("relay websocket send failed")
                o += n
                remaining -= n
            }
        }

        override fun close() = this@WebSocketRelaySocket.close()
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val req = Request.Builder().url(config.webSocketUrl).build()
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.put(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text messages are invalid in the protocol (§1).
                failure = IOException("unexpected text message from relay")
                webSocket.cancel()
                finish()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                opened.countDown()
                finish()
            }
        })
        val waited = if (timeout > 0) opened.await(timeout.toLong(), TimeUnit.MILLISECONDS) else opened.await(30, TimeUnit.SECONDS)
        if (!waited) {
            ws?.cancel()
            throw SocketTimeoutException("relay websocket connect timed out")
        }
        failure?.let { throw IOException("relay websocket: ${it.message}", it) }
        val previous = readTimeoutMs
        readTimeoutMs = RelayTcpSocket.HANDSHAKE_TIMEOUT_MS
        try {
            RelayProtocol.handshake(input, output, fingerprint, config.token, config.keyBytes)
        } catch (e: IOException) {
            close()
            throw e
        } finally {
            readTimeoutMs = previous
        }
        connected = true
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    private fun finish() {
        if (remoteClosed.compareAndSet(false, true)) incoming.put(EOF)
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun isConnected(): Boolean = connected
    override fun isClosed(): Boolean = closed.get()
    override fun isInputShutdown(): Boolean = remoteClosed.get()
    override fun isOutputShutdown(): Boolean = closed.get()
    override fun setSoTimeout(timeout: Int) { readTimeoutMs = timeout }
    override fun getSoTimeout(): Int = readTimeoutMs
    override fun getInetAddress(): InetAddress = RelayDns.lookup(RELAY_HOST).first()
    override fun getPort(): Int = config.port
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(inetAddress, port)
    override fun shutdownInput() = Unit
    override fun shutdownOutput() { ws?.close(1000, null) }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ws?.close(1000, null)
            incoming.offer(EOF)
        }
    }

    companion object {
        private val EOF = ByteArray(0)
        private const val MAX_MESSAGE = 16 * 1024
    }
}

/** Every socket OkHttp asks for goes to the relay (TCP or WebSocket). */
class RelaySocketFactory(
    private val config: RelayConfig,
    private val fingerprint: String,
    private val wsClient: OkHttpClient,
) : SocketFactory() {
    override fun createSocket(): Socket = when (config.transport) {
        RelayConfig.Transport.TCP -> RelayTcpSocket(config, fingerprint)
        RelayConfig.Transport.WEBSOCKET -> WebSocketRelaySocket(wsClient, config, fingerprint)
    }

    override fun createSocket(host: String?, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(RELAY_HOST, port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket(RELAY_HOST, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(RELAY_HOST, port)
}

/**
 * A base OkHttpClient whose connections go through the relay. Pass it as `base` to
 * `TlsClientFactory.create` so pinning and the client certificate are applied on top, unchanged.
 */
fun relayBaseClient(base: OkHttpClient, config: RelayConfig, fingerprint: String, wsClient: OkHttpClient = OkHttpClient()): OkHttpClient =
    base.newBuilder()
        .socketFactory(RelaySocketFactory(config, fingerprint, wsClient))
        .dns(RelayDns)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()
