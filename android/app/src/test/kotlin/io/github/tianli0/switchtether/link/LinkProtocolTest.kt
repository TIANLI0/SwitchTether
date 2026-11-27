package io.github.tianli0.switchtether.link

import io.github.tianli0.switchtether.forward.ActiveForward
import io.github.tianli0.switchtether.forward.StartResult
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.notify.LocalAction
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI ↔ `:link` payloads survive the trip, and garbage never becomes a success. */
class LinkProtocolTest {
    private val forward = ActiveForward(
        leaseId = "l1", remotePort = 5173, localPort = 15173, process = "vite", expiresAtMs = 99,
        status = TunnelStatus.OPEN, viaRelay = true, connections = 2, totalConnections = 7, bytesDown = 10, bytesUp = 20,
    )

    @Test
    fun everyStartResultRoundTrips() {
        val all = listOf(
            StartResult.Started(forward, listening = true),
            StartResult.Started(forward, listening = null),
            StartResult.NotConfirmed,
            StartResult.NoStepUpKey,
            StartResult.LocalPortBusy(15173, 5173),
            StartResult.Failed("nope"),
        )
        all.forEach { assertEquals(it, LinkProtocol.decodeResult(LinkProtocol.encodeResult(it))) }
    }

    @Test
    fun aMissingOrBrokenReplyIsAFailureNotAStart() {
        assertTrue(LinkProtocol.decodeResult(null) is StartResult.Failed)
        assertTrue(LinkProtocol.decodeResult("{not json") is StartResult.Failed)
        assertTrue(LinkProtocol.decodeResult("{\"kind\":\"started\"}") is StartResult.Failed)
    }

    @Test
    fun stateSummaryAndLeaseRoundTrip() {
        val state = LinkState(pins = listOf("a", "b"), forwards = listOf(forward))
        assertEquals(state, LinkProtocol.decodeState(LinkProtocol.encodeState(state)))
        assertNull(LinkProtocol.decodeState("garbage"))

        val summary = SessionSummary(id = "s1", title = "Fix it", liveness = "live", status = "busy", managed = true)
        assertEquals(summary, LinkProtocol.decodeSummary(LinkProtocol.encodeSummary(summary)))
        assertNull(LinkProtocol.decodeSummary(null))

        val lease = ForwardView(id = "l1", port = 5173, expiresAtMs = 5, listening = false)
        assertEquals(lease, LinkProtocol.decodeLease(LinkProtocol.encodeLease(lease)))
    }

    @Test
    fun triStateBooleansAndLocalActions() {
        listOf(null, false, true).forEach { assertEquals(it, LinkProtocol.tri(LinkProtocol.tri(it))) }
        assertNull(LinkProtocol.tri(7))
        LocalAction.entries.forEach { assertEquals(it, LinkProtocol.localAction(it.name)) }
        assertNull(LinkProtocol.localAction("REBOOT"))
    }
}
