package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.events.EventStreamReducer
import io.github.tianli0.switchtether.remote.events.StreamCursor
import io.github.tianli0.switchtether.remote.events.StreamDecision
import io.github.tianli0.switchtether.remote.events.StreamFrame
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.remote.stepup.StepUpPayload
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStreamReducerTest {
    private fun ev(seq: Long, epoch: String = "E1") = StreamFrame.Event(seq, epoch, "", "session.event", JsonNull)

    @Test
    fun appliesInOrderAndDropsDuplicates() {
        var c = StreamCursor("E1", 10)
        val decisions = listOf(ev(11), ev(11), ev(9), ev(14), ev(12)).map { f ->
            val (nc, d) = EventStreamReducer.reduce(c, f)
            c = nc
            d
        }
        assertTrue(decisions[0] is StreamDecision.Apply)
        assertEquals(StreamDecision.Duplicate, decisions[1])
        assertEquals(StreamDecision.Duplicate, decisions[2])
        // gaps are normal (device-scoped events of other devices are skipped)
        assertTrue(decisions[3] is StreamDecision.Apply)
        assertEquals(StreamDecision.Duplicate, decisions[4])
        assertEquals(14, c.lastSeq)
    }

    @Test
    fun resyncFrameResetsCursor() {
        val (c, d) = EventStreamReducer.reduce(StreamCursor("E1", 5), StreamFrame.Resync("E2", 99, "epoch_changed"))
        assertEquals(StreamCursor("E2", 99), c)
        assertEquals(StreamDecision.Resync("epoch_changed"), d)
        // after resync, events <= headSeq are duplicates, later ones apply
        assertEquals(StreamDecision.Duplicate, EventStreamReducer.reduce(c, ev(99, "E2")).second)
        assertTrue(EventStreamReducer.reduce(c, ev(100, "E2")).second is StreamDecision.Apply)
    }

    @Test
    fun epochChangeWithoutResyncFrameForcesResync() {
        val (c, d) = EventStreamReducer.reduce(StreamCursor("E1", 50), ev(3, "E2"))
        assertTrue(d is StreamDecision.Resync)
        assertEquals(StreamCursor("E2", 3), c)
    }

    @Test
    fun firstConnectionAdoptsEpoch() {
        val (c, d) = EventStreamReducer.reduce(StreamCursor.INITIAL, ev(1, "E9"))
        assertTrue(d is StreamDecision.Apply)
        assertEquals(StreamCursor("E9", 1), c)
    }

    @Test
    fun parsesFrames() {
        assertEquals(StreamFrame.Resync("e", 7, "gap"), StreamFrame.parse("""{"type":"resync","epoch":"e","headSeq":7,"reason":"gap"}"""))
        val f = StreamFrame.parse("""{"type":"event","seq":3,"epoch":"e","ts":"t","topic":"session.event","data":{"id":"x"}}""")
        assertTrue(f is StreamFrame.Event && f.seq == 3L && f.topic == "session.event")
        assertTrue(StreamFrame.parse("""{"type":"pong","ts":"t"}""") is StreamFrame.Pong)
        assertTrue(StreamFrame.parse("garbage") is StreamFrame.Unknown)
    }
}

class AgentEventLogTest {
    private fun e(id: String, seq: Long, kind: String, data: String = "{}", parent: String? = null) = AgentEvent(
        id = id, seq = seq, sessionId = "s", kind = kind, parentId = parent,
        data = ProtocolJson.parseToJsonElement(data).jsonObject,
    )

    @Test
    fun replacesByIdAndKeepsSeqOrder() {
        var log = AgentEventLog.of(listOf(e("b", 2, "assistant_message", """{"text":"he"}"""), e("a", 1, "user_message")))
        assertEquals(listOf("a", "b"), log.events.map { it.id })
        log = log.upsert(e("b", 2, "assistant_message", """{"text":"hello"}"""))
        assertEquals(2, log.size)
        assertEquals("hello", log["b"]!!.data["text"].toString().trim('"'))
        // identical upsert returns the same instance (no recomposition churn)
        assertSame(log, log.upsert(e("a", 1, "user_message")))
        // older page merges
        log = log.upsert(listOf(e("z", 0, "system"), e("a", 1, "user_message")))
        assertEquals(listOf("z", "a", "b"), log.events.map { it.id })
        assertEquals(0L, log.minSeq)
        assertEquals(2L, log.maxSeq)
    }

    @Test
    fun renderItemsAttachResultsNestSubagentsAndFoldResolutions() {
        val log = AgentEventLog.of(
            listOf(
                e("c1", 1, "tool_call", """{"callId":"k1","name":"Task","category":"task","status":"running"}"""),
                e("s1", 2, "assistant_message", """{"text":"sub"}""", parent = "c1"),
                e("s2", 3, "tool_call", """{"callId":"k2","name":"Bash","category":"shell"}""", parent = "c1"),
                e("r2", 4, "tool_result", """{"callId":"k2","text":"ok"}""", parent = "c1"),
                e("r1", 5, "tool_result", """{"callId":"k1","text":"done"}"""),
                e("orphan", 6, "tool_result", """{"callId":"zz","text":"?"}"""),
                e("ap", 7, "approval_request", """{"requestId":"q1","options":[]}"""),
                e("apr", 8, "approval_resolved", """{"requestId":"q1","decision":"allow","by":"desktop"}"""),
                e("u", 9, "brand_new_kind"),
            ),
        )
        val items = log.renderItems()
        assertEquals(listOf("c1", "s1", "s2", "orphan", "ap", "u"), items.map { it.key })
        assertEquals("r1", items[0].attachedResult?.id)
        assertTrue(items[0].hasChildren)
        assertEquals(1, items[1].depth)
        assertEquals("r2", items[2].attachedResult?.id)
        assertEquals("apr", items[4].resolution?.id)
        assertTrue(log.pendingRequests().isEmpty())

        val collapsed = log.renderItems(collapsedParents = setOf("c1"))
        assertEquals(listOf("c1", "orphan", "ap", "u"), collapsed.map { it.key })
    }

    @Test
    fun subagentRowIsAChildOfItsAgentCallNotItsOwnParent() {
        val items = AgentEventLog.of(
            listOf(
                e("c1", 1, "tool_call", """{"callId":"k1","name":"Agent","category":"task"}"""),
                e("sa:k1", 2, "subagent", """{"callId":"k1","status":"running"}""", parent = "k1"),
            ),
        ).renderItems()
        assertEquals(listOf("c1", "sa:k1"), items.map { it.key })
        assertTrue(items[0].hasChildren)
        assertEquals(1, items[1].depth)
        assertTrue(!items[1].hasChildren)
    }

    @Test
    fun inPlaceResolvedRequestsAreNotPending() {
        val open = e("ap", 1, "approval_request", """{"requestId":"q1","options":[{"id":"allow","label":"Allow"}],"resolved":false}""")
        val q = e("qq", 2, "question", """{"requestId":"q2","questions":[]}""")
        var log = AgentEventLog.of(listOf(open, q))
        assertEquals(listOf("ap", "qq"), log.pendingRequests().map { it.id })
        log = log.upsert(e("ap", 1, "approval_request", """{"requestId":"q1","resolved":true,"decision":"allow","by":"phone"}"""))
        assertEquals(listOf("qq"), log.pendingRequests().map { it.id })
    }
}

class StepUpPayloadTest {
    @Test
    fun exactBytes() {
        val bytes = StepUpPayload.build("dev-1", "ch-2", "bm9uY2U", "terminal")
        assertArrayEquals("switchtether-stepup-v1\ndev-1\nch-2\nbm9uY2U\nterminal".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInjectedNewline() {
        StepUpPayload.build("dev\nevil", "c", "n", "terminal")
    }

    @Test
    fun ecdsaP256Sha256DerSignatureVerifies() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val payload = StepUpPayload.build("d", "c", "n", "files_write")
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(payload)
            sign()
        }
        assertEquals(0x30, sig[0].toInt() and 0xff) // ASN.1 DER SEQUENCE
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(kp.public)
            update(payload)
            verify(sig)
        }
        assertTrue(ok)
        // SPKI encoding of the public key is what is sent as stepUpPublicKey
        assertEquals("X.509", kp.public.format)
    }

    @Test
    fun claudeTaskSubagentNestsByCallId() {
        val log = io.github.tianli0.switchtether.remote.session.AgentEventLog.of(
            listOf(
                io.github.tianli0.switchtether.remote.protocol.AgentEvent(
                    id = "m1:0",
                    seq = 1,
                    kind = io.github.tianli0.switchtether.remote.protocol.EventKinds.TOOL_CALL,
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("callId", kotlinx.serialization.json.JsonPrimitive("toolu_sub_1"))
                        put("name", kotlinx.serialization.json.JsonPrimitive("Agent"))
                        put("category", kotlinx.serialization.json.JsonPrimitive("task"))
                        put("status", kotlinx.serialization.json.JsonPrimitive("running"))
                    },
                ),
                io.github.tianli0.switchtether.remote.protocol.AgentEvent(
                    id = "m2:0",
                    seq = 2,
                    kind = io.github.tianli0.switchtether.remote.protocol.EventKinds.TOOL_CALL,
                    parentId = "toolu_sub_1",
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("callId", kotlinx.serialization.json.JsonPrimitive("toolu_inner"))
                        put("name", kotlinx.serialization.json.JsonPrimitive("Read"))
                        put("status", kotlinx.serialization.json.JsonPrimitive("completed"))
                    },
                ),
            ),
        )
        val items = log.renderItems()
        assertEquals(2, items.size)
        assertTrue(items[0].hasChildren)
        assertEquals(1, items[1].depth)
        assertEquals(1, log.renderItems(collapsedParents = setOf("m1:0")).size)
        assertEquals(1, log.renderItems(collapsedParents = setOf("toolu_sub_1")).size)
    }
}
