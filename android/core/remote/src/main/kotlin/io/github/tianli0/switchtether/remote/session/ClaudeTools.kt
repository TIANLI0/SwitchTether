package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.arr
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.double
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Claude Code session tools (`POST /v1/sessions/{id}/claude/{action}`, docs/PROTOCOL.md): the
 * IDE extension's session features for a Claude Code process SwitchTether started. The session
 * detail says whether they are offered (`claudeTools: true`); responses are parsed leniently here.
 */
object ClaudeTools {
    const val PERMISSION_MODE = "permission_mode"
    const val CONTEXT_USAGE = "context_usage"
    const val TASK_OUTPUT = "task_output"
    const val PLAN = "plan"
    const val MCP_STATUS = "mcp_status"
    const val MCP_RECONNECT = "mcp_reconnect"
    const val RUN_IN_BACKGROUND = "run_in_background"
    const val CANCEL_MESSAGE = "cancel_message"
    const val SIDE_QUESTION = "side_question"
    const val RENAME = "rename"
    const val REWIND = "rewind"
    const val EXPORT = "export"

    /** The slash commands the running agent offers (`{commands: [{name, description, argumentHint}], source}`). */
    const val COMMANDS = "commands"

    /**
     * Permission modes the phone offers in a running session (Shift+Tab in the CLI; bypass only works
     * when started with it). The desktop refuses a mode the installed Claude Code does not know.
     */
    val PERMISSION_MODES: List<String> = listOf("default", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions")

    /** Tools a running foreground call of which can be moved to the background (Ctrl+B in the IDE). */
    private val BACKGROUNDABLE_TOOLS = setOf("Bash", "Agent", "Task")

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Only ids Claude Code gave the message (UUIDs) can be rewound to; echoes of other inputs cannot. */
    fun isMessageUuid(id: String?): Boolean = id != null && UUID_RE.matches(id)

    /** A user message files can be restored to the state before. */
    fun canRewindTo(event: AgentEvent): Boolean = event.kind == EventKinds.USER_MESSAGE && isMessageUuid(event.id)

    /**
     * The tool use id of a running foreground Bash / Agent call that may be sent to the background,
     * or null. Works on a `tool_call` (its `callId`) and on a `subagent` row (its `callId`).
     */
    fun backgroundableCallId(event: AgentEvent): String? {
        val d = event.data
        if (d.bool("background") == true) return null
        val status = d.str("status")
        if (status != "running" && status != "pending") return null
        return when (event.kind) {
            EventKinds.TOOL_CALL -> d.str("name")?.takeIf { it in BACKGROUNDABLE_TOOLS }?.let { d.str("callId") ?: event.id }
            EventKinds.SUBAGENT -> d.str("callId") ?: event.parentId
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    fun parseContextUsage(e: JsonElement?): ContextUsage {
        val o = e.obj() ?: JsonObject(emptyMap())
        val cats = o["categories"].arr().orEmpty().mapNotNull { it.obj() }.map { c ->
            ContextCategory(
                id = c.str("id") ?: c.str("name").orEmpty(),
                name = c.str("name") ?: c.str("id").orEmpty(),
                tokens = c.long("tokens") ?: 0,
                kind = c.str("kind") ?: "used",
            )
        }
        val total = o.long("totalTokens") ?: cats.filter { it.kind == "used" }.sumOf { it.tokens }
        val max = o.long("maxTokens") ?: 0
        val pct = o.double("percentage") ?: if (max > 0) total * 100.0 / max else 0.0
        return ContextUsage(total, max, pct, cats)
    }

    fun parseTaskOutput(e: JsonElement?): TaskOutput {
        val o = e.obj() ?: JsonObject(emptyMap())
        return TaskOutput(o.str("output").orEmpty(), o.long("totalBytes"), o.bool("truncated") == true)
    }

    fun parsePlan(e: JsonElement?): PlanView {
        val o = e.obj() ?: JsonObject(emptyMap())
        val content = o.str("content")?.takeIf { it.isNotBlank() }
        return PlanView(exists = (o.bool("exists") ?: (content != null)) && content != null, content = content, path = o.str("path"))
    }

    fun parseMcpStatus(e: JsonElement?): List<McpServerStatus> =
        e.obj()?.get("servers").arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { s ->
            val name = s.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpServerStatus(
                name = name,
                status = s.str("status") ?: "unknown",
                title = s.str("title"),
                version = s.str("version"),
                transport = s.str("transport"),
                scope = s.str("scope"),
                tools = s.long("tools")?.toInt(),
                error = s.str("error")?.takeIf { it.isNotBlank() },
            )
        }

    fun parseRewind(e: JsonElement?): RewindResult {
        val o = e.obj() ?: JsonObject(emptyMap())
        return RewindResult(
            dryRun = o.bool("dryRun") ?: true,
            canRewind = o.bool("canRewind") == true,
            error = o.str("error")?.takeIf { it.isNotBlank() },
            filesChanged = o["filesChanged"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            insertions = o.long("insertions"),
            deletions = o.long("deletions"),
        )
    }

    fun parseExport(e: JsonElement?): ExportedConversation {
        val o = e.obj() ?: JsonObject(emptyMap())
        return ExportedConversation(o.str("text").orEmpty(), o.str("filename")?.takeIf { it.isNotBlank() })
    }

    /** `{answer}` of a side question. */
    fun parseAnswer(e: JsonElement?): String = e.obj()?.str("answer").orEmpty()

    fun parseMode(e: JsonElement?, fallback: String): String = e.obj()?.str("mode")?.takeIf { it.isNotBlank() } ?: fallback

    fun parseFlag(e: JsonElement?, key: String): Boolean = e.obj()?.bool(key) == true

    /**
     * `{commands: [...]}` of the `commands` tool: names (and `aliases`) without the leading slash,
     * blank and repeated names dropped, missing texts as `""`. Anything else (an older desktop's
     * error body) is no list.
     */
    fun parseCommands(e: JsonElement?): List<SlashCommand> =
        e.obj()?.get("commands").arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { c ->
            val name = c.str("name")?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return@mapNotNull null
            val aliases = c["aliases"].arr().orEmpty()
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.trimStart('/') }
                .filter { it.isNotEmpty() && it.none(Char::isWhitespace) && !it.equals(name, ignoreCase = true) }
                .distinctBy { it.lowercase() }
            SlashCommand(name, c.str("description")?.trim().orEmpty(), c.str("argumentHint")?.trim().orEmpty(), aliases)
        }.distinctBy { it.name.lowercase() }

    /** `messageUuid` of a `POST …/messages` response (Claude Code sessions SwitchTether started), else null. */
    fun messageUuidOf(sendResponse: JsonElement?): String? = sendResponse.obj()?.str("messageUuid")?.takeIf { isMessageUuid(it) }
}

/** One row of `/context`: `kind` is `used`, `deferred`, `buffer` or `free`. */
data class ContextCategory(val id: String, val name: String, val tokens: Long, val kind: String)

data class ContextUsage(
    val totalTokens: Long,
    val maxTokens: Long,
    val percentage: Double,
    val categories: List<ContextCategory>,
)

/** The newest output of a background shell task (64 KiB at most). */
data class TaskOutput(val output: String, val totalBytes: Long?, val truncated: Boolean)

/** The plan-mode plan (markdown) when one exists. */
data class PlanView(val exists: Boolean, val content: String?, val path: String?)

data class McpServerStatus(
    val name: String,
    /** `connected`, `failed`, `needs-auth`, `pending`, `disabled`, … */
    val status: String,
    val title: String? = null,
    val version: String? = null,
    val transport: String? = null,
    val scope: String? = null,
    val tools: Int? = null,
    val error: String? = null,
)

/** Result of `rewind` (files only, never the conversation); paths relative to the session's folder. */
data class RewindResult(
    val dryRun: Boolean,
    val canRewind: Boolean,
    val error: String?,
    val filesChanged: List<String>,
    val insertions: Long?,
    val deletions: Long?,
)

data class ExportedConversation(val text: String, val filename: String?)

/** One slash command of the agent (`/name args`): run by sending it as an ordinary message. */
@kotlinx.serialization.Serializable
data class SlashCommand(
    /** Without the leading slash. */
    val name: String,
    val description: String = "",
    /** What follows the name (`[instructions]`, `<pr-number>`), `""` when it takes nothing. */
    val argumentHint: String = "",
    /** Other names of the same command (`/cost` for `/usage`), without the slash; usually none. */
    val aliases: List<String> = emptyList(),
)
