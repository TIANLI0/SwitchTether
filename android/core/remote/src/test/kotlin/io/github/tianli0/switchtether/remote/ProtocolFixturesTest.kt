package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.ApiErrorEnvelope
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.Capability
import io.github.tianli0.switchtether.remote.protocol.ControlLevel
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.Hello
import io.github.tianli0.switchtether.remote.protocol.PairRequest
import io.github.tianli0.switchtether.remote.protocol.PairResponse
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.str
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses the shared JSON fixtures in <repo>/protocol/fixtures (also parsed by the Rust tests). */
class ProtocolFixturesTest {
    private fun fixture(name: String): String {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        val f = File(dir, name)
        assertTrue("fixture missing: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun hello() {
        val h = ProtocolJson.decodeFromString(Hello.serializer(), fixture("hello.json"))
        assertEquals(1, h.protocolVersion)
        assertEquals("DESKTOP-01", h.serverName)
        assertTrue(h.paired)
        assertEquals("0c3e9a77-2b9f-4f61-8a43-5d1b2e3f4a5b", h.deviceId)
        assertEquals(Hello.Compatibility.OK, h.compatibility())
        assertEquals(Hello.Compatibility.DESKTOP_TOO_OLD, h.copy(protocolVersion = 0, minProtocolVersion = 0).compatibility())
        assertEquals(Hello.Compatibility.APP_TOO_OLD, h.copy(protocolVersion = 3, minProtocolVersion = 2).compatibility())
    }

    @Test
    fun pairRequestRoundTripUsesWireFieldNames() {
        val req = ProtocolJson.decodeFromString(PairRequest.serializer(), fixture("pair_request.json"))
        assertEquals("android", req.platform)
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE", req.stepUpPublicKey)
        val encoded = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(PairRequest.serializer(), req)).jsonObject
        val expected = ProtocolJson.parseToJsonElement(fixture("pair_request.json")).jsonObject
        assertEquals(expected, encoded)
    }

    @Test
    fun pairRequestWithoutStepUpKeyOmitsField() {
        val req = PairRequest(pairingId = "p", secret = "s", deviceName = "d", stepUpPublicKey = null)
        val o = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(PairRequest.serializer(), req)).jsonObject
        assertFalse(o.containsKey("stepUpPublicKey"))
        assertEquals("android", o.str("platform"))
    }

    @Test
    fun pairResponse() {
        val r = ProtocolJson.decodeFromString(PairResponse.serializer(), fixture("pair_response.json"))
        assertEquals("042317", r.verificationCode)
        assertTrue(r.stepUpEnabled)
        assertEquals(64, r.serverFingerprint.length)
    }

    @Test
    fun errorEnvelope() {
        val e = ProtocolJson.decodeFromString(ApiErrorEnvelope.serializer(), fixture("error.json")).error
        assertEquals(ErrorCodes.STEP_UP_REQUIRED, e.code)
        assertEquals("terminal", e.details?.str("scope"))
        assertFalse(e.retryable)
        assertNull(e.requestId)
    }

    @Test
    fun sessionShapesFromServer() {
        val page = RemoteApi.parseSessionPage(
            ProtocolJson.parseToJsonElement(
                """{"items":[{"id":"claude:4160f711","agent":"claude_code","origin":"vscode","liveness":"live",
                   "status":"idle","control":"observe","futureField":42}],"nextCursor":null}""",
            ),
        )
        assertEquals(1, page.items.size)
        assertTrue(page.items[0].isLive)
        assertEquals(ControlLevel.OBSERVE, page.items[0].controlLevel)

        val detail = RemoteApi.parseSessionDetail(
            ProtocolJson.parseToJsonElement(
                """{"id":"claude:1","agent":"claude_code","liveness":"live","control":"observe",
                   "capabilities":{"sendMessage":"BLOCKED","approve":"BLOCKED","readHistory":"VERIFIED","interrupt":"WEIRD"},
                   "pendingRequests":[{"id":"e1","seq":3,"kind":"approval_request","data":{"requestId":"r1","options":[]}}]}""",
            ),
        )
        assertEquals(CapStatus.BLOCKED, detail.capabilities.status(Capability.SEND_MESSAGE))
        assertEquals(CapStatus.VERIFIED, detail.capabilities.status(Capability.READ_HISTORY))
        assertEquals(CapStatus.UNKNOWN, detail.capabilities.status(Capability.INTERRUPT))
        assertEquals(CapStatus.UNKNOWN, detail.capabilities.status(Capability.ANSWER_QUESTION))
        assertEquals("r1", detail.pendingRequests.single().data.str("requestId"))

        val events = RemoteApi.parseEventPage(
            ProtocolJson.parseToJsonElement(
                """{"events":[{"id":"b","seq":2,"kind":"assistant_message","data":{"text":"hi"}},
                             {"id":"a","seq":1,"kind":"user_message","data":{"text":"yo"}}],"hasMore":true,"lastSeq":2}""",
            ),
            limit = 300,
            olderPossible = true,
        )
        assertEquals(listOf("a", "b"), events.events.map { it.id })
        assertTrue(events.hasMoreBefore)
    }
}
