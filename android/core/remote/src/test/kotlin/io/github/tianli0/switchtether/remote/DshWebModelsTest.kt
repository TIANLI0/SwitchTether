package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.protocol.DshWebOpen
import io.github.tianli0.switchtether.remote.protocol.DshWebStates
import io.github.tianli0.switchtether.remote.protocol.DshWebStatus
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §7.14 fixtures (also checked by the Rust tests). */
class DshWebModelsTest {
    private fun fixture(name: String): String {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        return File(dir, name).readText()
    }

    @Test
    fun statusFixture() {
        val o = ProtocolJson.parseToJsonElement(fixture("dsh_web_status.json")).jsonObject
        val running = ProtocolJson.decodeFromJsonElement(DshWebStatus.serializer(), o["running"]!!)
        assertTrue(running.running)
        assertEquals(52166, running.port)
        assertTrue(running.owned)
        val stopped = ProtocolJson.decodeFromJsonElement(DshWebStatus.serializer(), o["stopped"]!!)
        assertEquals(DshWebStates.STOPPED, stopped.state)
        assertFalse(stopped.running)
        assertNull(stopped.port)
        assertTrue(stopped.error!!.contains("exited"))
    }

    @Test
    fun openFixture() {
        val v = ProtocolJson.decodeFromString(DshWebOpen.serializer(), fixture("dsh_web_open.json"))
        assertTrue(v.status.running)
        assertEquals(v.status.port, v.forward!!.port)
        assertEquals(true, v.forward!!.listening)
        assertTrue(v.launchPath!!.startsWith("/?token="))
        // While it starts there is only a status.
        val starting = ProtocolJson.decodeFromString(DshWebOpen.serializer(), """{"status":{"state":"starting","owned":true}}""")
        assertNull(starting.forward)
        assertNull(starting.launchPath)
        assertFalse(starting.status.running)
    }
}
