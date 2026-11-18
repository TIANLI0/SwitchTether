package io.github.tianli0.switchtether.remote.wake

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.serialization.Serializable

/**
 * A wakeable interface of the paired computer (`GET /v1/status` → `wake`): its MAC address, the
 * directed broadcast address of its IPv4 subnet and its own address there.
 */
@Serializable
data class WakeTarget(
    val mac: String = "",
    val broadcast: String = "",
    val address: String = "",
)

/**
 * Wake-on-LAN magic packets (6 × 0xFF followed by the MAC 16 times), sent as UDP broadcast to
 * ports 9 and 7. Only works when the phone is on the computer's network and the computer's own
 * firmware / adapter settings allow it; SwitchTether never changes those settings.
 */
object WakeOnLan {
    val PORTS = listOf(9, 7)
    private val MAC = Regex("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")

    fun macBytes(mac: String): ByteArray? {
        if (!MAC.matches(mac.trim())) return null
        val bytes = mac.trim().split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
        return bytes
    }

    fun magicPacket(mac: String): ByteArray? {
        val m = macBytes(mac) ?: return null
        val out = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) out[i] = 0xFF.toByte()
        for (r in 0 until 16) m.copyInto(out, 6 + r * 6)
        return out
    }

    /** IPv4 dotted quad only (never resolves names: no DNS lookups for a broadcast). */
    private fun ipv4(text: String): InetAddress? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val b = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
        return InetAddress.getByAddress(ByteArray(4) { b[it].toByte() })
    }

    /** (destination, packet) pairs for the targets: each subnet broadcast plus 255.255.255.255. */
    fun datagrams(targets: List<WakeTarget>): List<Pair<InetAddress, ByteArray>> {
        val out = ArrayList<Pair<InetAddress, ByteArray>>()
        val limited = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        for (t in targets.distinctBy { it.mac.lowercase() to it.broadcast }) {
            val packet = magicPacket(t.mac) ?: continue
            listOfNotNull(ipv4(t.broadcast), limited).distinct().forEach { out += it to packet }
        }
        return out.distinctBy { it.first to it.second.contentHashCode() }
    }

    /**
     * Sends the packets (blocking; call off the main thread). Returns how many datagrams were
     * sent; failures (no Wi-Fi, local network permission missing) are skipped.
     */
    fun send(targets: List<WakeTarget>, socketFactory: () -> DatagramSocket = { DatagramSocket() }): Int {
        val datagrams = datagrams(targets)
        if (datagrams.isEmpty()) return 0
        var sent = 0
        runCatching {
            socketFactory().use { socket ->
                socket.broadcast = true
                repeat(2) { // UDP is lossy; a second burst costs nothing
                    for ((dest, packet) in datagrams) for (port in PORTS) {
                        if (runCatching { socket.send(DatagramPacket(packet, packet.size, dest, port)) }.isSuccess) sent++
                    }
                }
            }
        }
        return sent
    }
}
