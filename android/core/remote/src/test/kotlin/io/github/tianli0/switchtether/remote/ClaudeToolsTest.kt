package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.session.ClaudeTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeToolsTest {
    private fun json(s: String) = ProtocolJson.parseToJsonElement(s)

    private fun ev(id: String, kind: String, data: String, parent: String? = null) =
        AgentEvent(id = id, kind = kind, parentId = parent, data = json(data).jsonObject)

    @Test fun sessionDetailCarriesClaudeToolsAndPermissionMode() {
        val d = RemoteApi.parseSessionDetail(
            json("""{"id":"claude:1","agent":"claude_code","liveness":"live","capabilities":{},"pendingRequests":[],"claudeTools":true,"permissionMode":"plan"}"""),
        )
        assertTrue(d.claudeTools)
        assertEquals("plan", d.permissionMode)

        val plain = RemoteApi.parseSessionDetail(json("""{"id":"codex:1","agent":"codex","capabilities":{}}"""))
        assertFalse(plain.claudeTools)
        assertNull(plain.permissionMode)

        // permissionMode may be null while the CLI has not reported it yet.
        val unknown = RemoteApi.parseSessionDetail(json("""{"id":"claude:2","claudeTools":true,"permissionMode":null}"""))
        assertTrue(unknown.claudeTools)
        assertNull(unknown.permissionMode)
    }

    @Test fun contextUsageKeepsCategoriesAndKinds() {
        val u = ClaudeTools.parseContextUsage(
            json(
                """{"totalTokens":42000,"maxTokens":200000,"percentage":21,"categories":[
                {"id":"system","name":"System prompt","tokens":3000,"kind":"used"},
                {"id":"tools","name":"MCP tools","tokens":9000,"kind":"deferred"},
                {"id":"buf","name":"Autocompact buffer","tokens":45000,"kind":"buffer"},
                {"id":"free","name":"Free space","tokens":143000,"kind":"free"}]}""",
            ),
        )
        assertEquals(42000L, u.totalTokens)
        assertEquals(200000L, u.maxTokens)
        assertEquals(21.0, u.percentage, 0.001)
        assertEquals(listOf("used", "deferred", "buffer", "free"), u.categories.map { it.kind })
        assertEquals("MCP tools", u.categories[1].name)

        // Missing percentage is derived; missing kind defaults to used.
        val derived = ClaudeTools.parseContextUsage(json("""{"totalTokens":50,"maxTokens":200,"categories":[{"name":"x","tokens":50}]}"""))
        assertEquals(25.0, derived.percentage, 0.001)
        assertEquals("used", derived.categories.single().kind)
    }

    @Test fun planMcpTaskOutputExportAndAnswer() {
        val plan = ClaudeTools.parsePlan(json("""{"exists":true,"content":"# Plan\n- a","path":"/p/plan.md"}"""))
        assertTrue(plan.exists)
        assertEquals("# Plan\n- a", plan.content)
        assertFalse(ClaudeTools.parsePlan(json("""{"exists":false,"content":null,"path":null}""")).exists)
        // "exists" without content cannot be shown as a plan.
        assertFalse(ClaudeTools.parsePlan(json("""{"exists":true}""")).exists)

        val servers = ClaudeTools.parseMcpStatus(
            json(
                """{"servers":[{"name":"github","status":"connected","title":"GitHub","version":"1.2","transport":"stdio","scope":"user","tools":12,"error":null},
                {"name":"broken","status":"failed","tools":null,"error":"spawn ENOENT"},{"status":"connected"}]}""",
            ),
        )
        assertEquals(listOf("github", "broken"), servers.map { it.name })
        assertEquals(12, servers[0].tools)
        assertEquals("spawn ENOENT", servers[1].error)
        assertNull(servers[1].tools)

        val out = ClaudeTools.parseTaskOutput(json("""{"output":"line 1\nline 2","totalBytes":90000,"truncated":true}"""))
        assertEquals("line 1\nline 2", out.output)
        assertEquals(90000L, out.totalBytes)
        assertTrue(out.truncated)

        val ex = ClaudeTools.parseExport(json("""{"text":"user: hi","filename":"conversation.txt"}"""))
        assertEquals("user: hi", ex.text)
        assertEquals("conversation.txt", ex.filename)

        assertEquals("42", ClaudeTools.parseAnswer(json("""{"answer":"42"}""")))
        assertEquals("plan", ClaudeTools.parseMode(json("""{"mode":"plan"}"""), "default"))
        assertEquals("acceptEdits", ClaudeTools.parseMode(json("{}"), "acceptEdits"))
        assertTrue(ClaudeTools.parseFlag(json("""{"cancelled":true}"""), "cancelled"))
        assertFalse(ClaudeTools.parseFlag(json("""{"backgrounded":false}"""), "backgrounded"))
    }

    @Test fun rewindDryRunAndRefusal() {
        val r = ClaudeTools.parseRewind(
            json("""{"dryRun":true,"canRewind":true,"error":null,"filesChanged":["src/a.rs","b.txt"],"insertions":4,"deletions":2}"""),
        )
        assertTrue(r.dryRun)
        assertTrue(r.canRewind)
        assertNull(r.error)
        assertEquals(listOf("src/a.rs", "b.txt"), r.filesChanged)
        assertEquals(4L, r.insertions)
        assertEquals(2L, r.deletions)

        val refused = ClaudeTools.parseRewind(json("""{"dryRun":true,"canRewind":false,"error":"No file checkpoint","filesChanged":[]}"""))
        assertFalse(refused.canRewind)
        assertEquals("No file checkpoint", refused.error)
        assertNull(refused.insertions)
    }

    @Test fun messageUuidsAndRewindTargets() {
        val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(uuid, ClaudeTools.messageUuidOf(json("""{"accepted":true,"messageUuid":"$uuid"}""")))
        assertNull(ClaudeTools.messageUuidOf(json("""{"accepted":true,"messageUuid":null}""")))
        assertNull(ClaudeTools.messageUuidOf(json("""{"accepted":true}""")))

        assertTrue(ClaudeTools.canRewindTo(ev(uuid, "user_message", """{"text":"hi"}""")))
        // Echoed inputs SwitchTether recorded itself ("in:…") are not Claude Code messages.
        assertFalse(ClaudeTools.canRewindTo(ev("in:abc", "user_message", """{"text":"hi"}""")))
        assertFalse(ClaudeTools.canRewindTo(ev(uuid, "assistant_message", """{"text":"hi"}""")))
    }

    @Test fun onlyRunningForegroundBashOrAgentCanGoToTheBackground() {
        val running = ev("t1", "tool_call", """{"callId":"toolu_1","name":"Bash","status":"running"}""")
        assertEquals("toolu_1", ClaudeTools.backgroundableCallId(running))
        assertEquals("toolu_2", ClaudeTools.backgroundableCallId(ev("t2", "tool_call", """{"callId":"toolu_2","name":"Agent","status":"running"}""")))
        assertEquals("toolu_3", ClaudeTools.backgroundableCallId(ev("t3", "tool_call", """{"callId":"toolu_3","name":"Task","status":"pending"}""")))
        // Already in the background, finished, or another tool: nothing to offer.
        assertNull(ClaudeTools.backgroundableCallId(ev("t4", "tool_call", """{"callId":"toolu_4","name":"Bash","status":"running","background":true}""")))
        assertNull(ClaudeTools.backgroundableCallId(ev("t5", "tool_call", """{"callId":"toolu_5","name":"Bash","status":"completed"}""")))
        assertNull(ClaudeTools.backgroundableCallId(ev("t6", "tool_call", """{"callId":"toolu_6","name":"Read","status":"running"}""")))
        // A running foreground sub-agent row points at its Agent call.
        assertEquals(
            "toolu_7",
            ClaudeTools.backgroundableCallId(ev("sa:toolu_7", "subagent", """{"callId":"toolu_7","status":"running","background":false}""", parent = "toolu_7")),
        )
        assertNull(ClaudeTools.backgroundableCallId(ev("sa:toolu_8", "subagent", """{"callId":"toolu_8","status":"running","background":true}""")))
        assertNull(ClaudeTools.backgroundableCallId(AgentEvent(id = "x", kind = "assistant_message", data = JsonObject(emptyMap()))))
    }
}
