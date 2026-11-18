package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.pairing.ManualAddress
import io.github.tianli0.switchtether.remote.pairing.PairingTarget
import io.github.tianli0.switchtether.remote.protocol.PairRequest
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.StatusView
import io.github.tianli0.switchtether.remote.wake.WakeOnLan
import io.github.tianli0.switchtether.remote.wake.WakeTarget
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PairingCodeAndWakeTest {
    @Test
    fun manualAddresses() {
        assertEquals(ManualAddress("192.168.1.20", 47615), ManualAddress.parse(" 192.168.1.20 "))
        assertEquals(ManualAddress("192.168.1.20", 48000), ManualAddress.parse("192.168.1.20:48000"))
        assertEquals(ManualAddress("pc.tail.ts.net", 47615), ManualAddress.parse("https://pc.tail.ts.net/"))
        assertEquals(ManualAddress("fd7a::1", 47615), ManualAddress.parse("fd7a::1"))
        assertEquals(ManualAddress("fd7a::1", 9), ManualAddress.parse("[fd7a::1]:9"))
        assertNull(ManualAddress.parse(""))
        assertNull(ManualAddress.parse("host:0"))
        assertNull(ManualAddress.parse("host:abc"))
        assertNull(ManualAddress.parse("bad host"))
        assertNull(ManualAddress.parse("-x"))
        assertNull(ManualAddress.parse("[fd7a::1"))
    }

    @Test
    fun codesAreSixDigits() {
        assertEquals("123456", PairingTarget.normalizeCode("123 456"))
        assertEquals("012345", PairingTarget.normalizeCode("012-345"))
        assertNull(PairingTarget.normalizeCode("12345"))
        assertNull(PairingTarget.normalizeCode("1234567"))
        assertFalse(PairingTarget.Code("PC", listOf("h"), 1, null, "123456").toString().contains("123456"))
    }

    @Test
    fun codePairRequestOmitsLinkFields() {
        val json = ProtocolJson.encodeToString(PairRequest.serializer(), PairRequest(code = "123456", deviceName = "P"))
        assertTrue(json, "\"code\":\"123456\"" in json)
        assertFalse(json, "pairingId" in json)
        assertFalse(json, "secret" in json)
    }

    @Test
    fun statusCarriesHostsAndWakeTargets() {
        val s = ProtocolJson.decodeFromString(
            StatusView.serializer(),
            """{"epoch":"e","headSeq":1,"hosts":["192.168.8.4","[fd7a::1]"],"wake":[{"mac":"00:11:22:33:44:55","broadcast":"192.168.8.255","address":"192.168.8.4"}]}""",
        )
        assertEquals(listOf("192.168.8.4", "[fd7a::1]"), s.hosts)
        assertEquals("00:11:22:33:44:55", s.wake.single().mac)
        // Older desktops: both absent.
        val old = ProtocolJson.decodeFromString(StatusView.serializer(), """{"epoch":"e","headSeq":1}""")
        assertTrue(old.hosts.isEmpty() && old.wake.isEmpty())
    }

    @Test
    fun magicPacketLayout() {
        val p = WakeOnLan.magicPacket("00:1A:2b:3c:4d:5e")!!
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        val mac = byteArrayOf(0x00, 0x1a, 0x2b, 0x3c, 0x4d, 0x5e)
        for (r in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + r * 6, 12 + r * 6))
        assertNull(WakeOnLan.magicPacket("00:00:00:00:00:00"))
        assertNull(WakeOnLan.magicPacket("ff:ff:ff:ff:ff:ff"))
        assertNull(WakeOnLan.magicPacket("00:11:22:33:44"))
        assertNull(WakeOnLan.magicPacket("zz:11:22:33:44:55"))
    }

    @Test
    fun datagramsGoToSubnetAndLimitedBroadcastOnly() {
        val d = WakeOnLan.datagrams(
            listOf(
                WakeTarget("00:11:22:33:44:55", "192.168.8.255", "192.168.8.4"),
                WakeTarget("00:11:22:33:44:55", "192.168.8.255", "192.168.8.4"), // duplicate
                WakeTarget("00:11:22:33:44:66", "evil.example.com", "x"), // never resolved
                WakeTarget("bad", "192.168.1.255", "x"),
            ),
        )
        val dests = d.map { it.first.hostAddress }.toSet()
        assertEquals(setOf("192.168.8.255", "255.255.255.255"), dests)
        assertEquals(3, d.size) // two for the first MAC, one (limited broadcast) for the second
    }

    @Test
    fun sendDeliversMagicPackets() {
        // Loopback receiver standing in for the broadcast address (a real broadcast is not
        // observable in a unit test); the socket factory redirects every send to it.
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { rx ->
            rx.soTimeout = 2_000
            val sent = WakeOnLan.send(listOf(WakeTarget("00:11:22:33:44:55", "192.168.8.255", "192.168.8.4"))) {
                object : DatagramSocket() {
                    override fun send(p: DatagramPacket) {
                        super.send(DatagramPacket(p.data, p.length, InetAddress.getLoopbackAddress(), rx.localPort))
                    }
                }
            }
            assertEquals(2 * 2 * 2, sent) // 2 bursts × 2 destinations × 2 ports
            val buf = ByteArray(200)
            val got = DatagramPacket(buf, buf.size)
            rx.receive(got)
            assertArrayEquals(WakeOnLan.magicPacket("00:11:22:33:44:55"), buf.copyOf(got.length))
        }
    }
}
