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
    fun codexQuestionFixture() {
        val event = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.AgentEvent.serializer(), fixture("agent_question.json"))
        assertEquals("question", event.kind)
        val questions = event.data["questions"] as kotlinx.serialization.json.JsonArray
        assertEquals("true", questions[1].jsonObject["multiSelect"].toString())
        assertEquals("false", questions[1].jsonObject["required"].toString())
    }

    @Test
    fun usageProjectsFixture() {
        val v = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.ProjectUsageView.serializer(), fixture("usage_projects.json"))
        val p = v.projects.single()
        assertEquals("SwitchTether", p.project)
        assertEquals(2, p.sessionCount)
        assertEquals(3.125, p.costUsd, 1e-9)
        assertEquals(52000L + 18000L + 2400000L + 96000L, p.totalTokens)
        assertEquals("claude:0f8e5a2c-7d1b-4c3e-9a6f-1b2c3d4e5f60", p.sessions[0].id)
        assertNull(p.sessions[1].title)
        assertNull(v.otherProjects)
        assertEquals(4L, v.unattributed!!.requests)
    }

    @Test
    fun remoteStatsFixture() {
        val v = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.RemoteStatsView.serializer(), fixture("remote_stats.json"))
        assertEquals(5L, v.totals.tasks)
        assertEquals(16L, v.totals.approvals)
        assertEquals(5_400_000L, v.totals.taskMs)
        assertEquals(3, v.days.size)
        assertEquals("2026-11-24", v.days[1].date)
        assertEquals(0L, v.days[1].counters.messages)
        assertEquals(v.totals.tasks, v.days.sumOf { it.tasks })
        assertEquals(v.totals.foregroundMs, v.devices.sumOf { it.foregroundMs })
        assertNull(v.devices[1].name)
        assertEquals(true, v.devices[1].revoked)
        assertEquals(6, v.sessions)
        assertEquals(7.8125, v.cost!!.costUsd, 1e-9)
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
    fun messageWithAttachmentsAndUploadStateRoundTrip() {
        val body = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.SendMessageBody.serializer(), fixture("send_message_attachments.json"))
        assertEquals(2, body.attachments.size)
        val encoded = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(io.github.tianli0.switchtether.remote.protocol.SendMessageBody.serializer(), body)).jsonObject
        assertEquals(ProtocolJson.parseToJsonElement(fixture("send_message_attachments.json")).jsonObject, encoded)
        val view = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.AttachmentView.serializer(), fixture("attachment_view.json"))
        assertEquals(2_097_152L, view.received)
        assertEquals(false, view.complete)
        val back = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(io.github.tianli0.switchtether.remote.protocol.AttachmentView.serializer(), view)).jsonObject
        assertEquals(ProtocolJson.parseToJsonElement(fixture("attachment_view.json")).jsonObject, back)
    }

    @Test
    fun codePairRequestRoundTrip() {
        val req = ProtocolJson.decodeFromString(PairRequest.serializer(), fixture("pair_request_code.json"))
        assertEquals("042917", req.code)
        assertEquals(null, req.pairingId)
        val encoded = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(PairRequest.serializer(), req)).jsonObject
        assertEquals(ProtocolJson.parseToJsonElement(fixture("pair_request_code.json")).jsonObject, encoded)
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

    @Test
    fun sessionOptionsAndModelOptionsFixtures() {
        val body = io.github.tianli0.switchtether.remote.protocol.SessionOptionsBody("c-41d2", model = "sonnet", effort = "high", permissionMode = "acceptEdits")
        assertEquals(ProtocolJson.parseToJsonElement(fixture("session_options.json")).jsonObject, ProtocolJson.encodeToJsonElement(io.github.tianli0.switchtether.remote.protocol.SessionOptionsBody.serializer(), body))
        val o = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.ModelOptions.serializer(), fixture("model_options.json"))
        assertEquals("agent", o.source)
        assertEquals(listOf("default", "sonnet", "haiku"), o.models.map { it.id })
        assertEquals(listOf("low", "medium", "high"), o.models[1].efforts)
        assertEquals(null, o.models[2].efforts)
        assertEquals(5, o.efforts.size)
        assertEquals(listOf("default", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions"), o.permissionModes.map { it.id })
        assertEquals(listOf("bypassPermissions"), o.permissionModes.filter { it.dangerous }.map { it.id })
        // Antigravity: models with their thinking levels; agy's own ACP modes plus `bypassPermissions`
        // (`agy acp --dangerously-skip-permissions`), the only dangerous one.
        val agy = ProtocolJson.decodeFromString(io.github.tianli0.switchtether.remote.protocol.ModelOptions.serializer(), fixture("model_options_agy.json"))
        assertEquals(listOf("gemini-4-neon", "gemini-pro-orchestrator", "claude-sonnet-5-5"), agy.models.map { it.id })
        assertEquals(listOf("low", "medium", "high", "xhigh"), agy.models[0].efforts)
        assertEquals(emptyList<String>(), agy.models[1].efforts)
        assertEquals(listOf("default", "acceptEdits", "auto", "plan", "bypassPermissions"), agy.permissionModes.map { it.id })
        assertEquals(listOf("bypassPermissions"), agy.permissionModes.filter { it.dangerous }.map { it.id })
    }

    @Test
    fun sessionSummaryActiveTasksFixture() {
        val o = ProtocolJson.parseToJsonElement(fixture("session_summary.json")).jsonObject
        val withTasks = ProtocolJson.decodeFromJsonElement(io.github.tianli0.switchtether.remote.protocol.SessionSummary.serializer(), o["idleWithTasks"]!!)
        assertEquals(2, withTasks.activeTasks)
        assertFalse(withTasks.isIdle())
        assertEquals("busy", withTasks.displayStatus())
        // An older desktop leaves the field out: idle, unless the phone saw work itself.
        val old = ProtocolJson.decodeFromJsonElement(io.github.tianli0.switchtether.remote.protocol.SessionSummary.serializer(), o["olderDesktop"]!!)
        assertNull(old.activeTasks)
        assertTrue(old.isIdle())
        assertFalse(old.isIdle(fallback = 1))
    }
}
