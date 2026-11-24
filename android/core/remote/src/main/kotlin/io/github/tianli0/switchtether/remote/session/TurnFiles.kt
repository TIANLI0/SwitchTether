package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Files the agent changed in the latest turn ("本轮改动"), computed from the timeline the phone
 * already has: edit / write tool calls (Claude Code Edit, Write, MultiEdit, NotebookEdit; Codex
 * apply_patch) and `file_change` events after the last user message. Paths are as the agent wrote
 * them (absolute or relative to the session's folder); [relativize] maps them into a repository.
 */
object TurnFiles {
    private val EDIT_TOOLS = setOf(
        "edit", "write", "multiedit", "notebookedit", "apply_patch", "str_replace", "create_file",
        "str_replace_based_edit_tool", "write_file", "edit_file",
    )
    private val SKIPPED_STATUS = setOf("denied", "failed", "pending")
    private val PATCH_HEADERS = listOf("*** Add File: ", "*** Update File: ", "*** Delete File: ", "*** Move to: ")

    /** Files changed since the last user message, in the order they were first touched. */
    fun latestTurn(events: List<AgentEvent>): List<String> {
        val start = events.indexOfLast { it.kind == EventKinds.USER_MESSAGE }
        val out = LinkedHashSet<String>()
        for (e in events.subList(start + 1, events.size)) out += filesOf(e)
        return out.toList()
    }

    /** Files one event changed (empty for anything else, and for a denied / failed edit). */
    fun filesOf(e: AgentEvent): List<String> = when (e.kind) {
        EventKinds.FILE_CHANGE -> listOfNotNull(clean(e.data.str("path")))
        EventKinds.TOOL_CALL -> toolFiles(e.data)
        else -> emptyList()
    }

    private fun toolFiles(data: JsonObject): List<String> {
        val name = data.str("name").orEmpty().lowercase().replace("-", "_")
        val category = data.str("category")
        if (name !in EDIT_TOOLS && category != "edit" && category != "write") return emptyList()
        if (data.str("status") in SKIPPED_STATUS) return emptyList()
        val input = data["input"].obj()
        val direct = input?.str("file_path", "path", "notebook_path", "file")?.let(::clean)
        if (direct != null) return listOf(direct)
        val patch = input?.str("patch", "input", "content")
            ?: (data["input"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return patch?.let(::patchPaths).orEmpty()
    }

    /** Paths named in an apply_patch envelope (`*** Update File: x`) or a unified diff (`+++ b/x`). */
    fun patchPaths(patch: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in patch.lineSequence()) {
            val line = raw.trimEnd('\r')
            PATCH_HEADERS.firstOrNull { line.startsWith(it) }?.let { h -> clean(line.removePrefix(h))?.let(out::add) }
            if (line.startsWith("+++ ") && !line.startsWith("+++ /dev/null")) {
                clean(line.removePrefix("+++ ").substringBefore('\t').removePrefix("b/"))?.let(out::add)
            }
        }
        return out.toList()
    }

    private fun clean(p: String?): String? = p?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }

    /**
     * [path] relative to the repository [root] (`/` separators), or null when it lies outside.
     * A relative [path] is taken relative to [cwd] (the session's folder), which may itself lie
     * below [root]. Windows paths compare case-insensitively.
     */
    fun relativize(root: String, path: String, cwd: String? = null): String? {
        val r = norm(root).trimEnd('/')
        if (r.isEmpty()) return null
        val absolute = isAbsolute(path)
        val full = when {
            absolute -> norm(path)
            cwd != null && isAbsolute(cwd) -> norm(cwd).trimEnd('/') + "/" + norm(path).removePrefix("./")
            else -> return norm(path).removePrefix("./").takeIf { it.isNotEmpty() && !it.startsWith("../") }
        }
        val resolved = resolveDots(full) ?: return null
        val windows = r.length >= 2 && r[1] == ':'
        val prefix = "$r/"
        return if (resolved.startsWith(prefix, ignoreCase = windows)) resolved.substring(prefix.length).takeIf { it.isNotEmpty() } else null
    }

    private fun norm(p: String) = p.trim().replace('\\', '/')

    private fun isAbsolute(p: String): Boolean {
        val n = norm(p)
        return n.startsWith("/") || (n.length >= 2 && n[1] == ':')
    }

    /** Folds `.` and `..` segments; null when `..` climbs above the start. */
    private fun resolveDots(p: String): String? {
        val lead = if (p.startsWith("/")) "/" else ""
        val parts = ArrayDeque<String>()
        for (seg in p.removePrefix("/").split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.size > 1 || (parts.size == 1 && lead == "/")) parts.removeLast() else return null
                else -> parts.addLast(seg)
            }
        }
        return lead + parts.joinToString("/")
    }
}
