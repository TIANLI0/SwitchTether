package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------------------------
// §7.6 Terminals
// ---------------------------------------------------------------------------------------------

@Serializable
data class ShellInfo(val id: String = "", val name: String = "", val path: String = "")

@Serializable
data class TerminalInfo(
    val id: String = "",
    val shell: String = "",
    val cwd: String = "",
    val title: String = "",
    val cols: Int = 80,
    val rows: Int = 24,
    val createdAtMs: Long? = null,
    val exited: Boolean = false,
    val exitCode: Int? = null,
    val version: Long = 0,
)

@Serializable
data class TerminalList(val terminals: List<TerminalInfo> = emptyList(), val shells: List<ShellInfo> = emptyList())

@Serializable
data class CreateTerminalBody(
    val commandId: String,
    val shell: String? = null,
    val cwd: String? = null,
    val cols: Int,
    val rows: Int,
)

/** One styled run of a terminal line. Colors: palette index "0".."255", "#rrggbb", or null (default). */
@Serializable
data class TermRun(
    val t: String = "",
    val fg: String? = null,
    val bg: String? = null,
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
    val inv: Boolean = false,
)

@Serializable
data class TermLine(val runs: List<TermRun> = emptyList(), val wrapped: Boolean = false) {
    val text: String get() = runs.joinToString("") { it.t }
}

@Serializable
data class TermCursor(val x: Int = 0, val y: Int = 0, val visible: Boolean = true)

@Serializable
data class ScrollbackPage(val lines: List<TermLine> = emptyList(), val total: Int = 0)

// ---------------------------------------------------------------------------------------------
// §7.7 Files & Git
// ---------------------------------------------------------------------------------------------

@Serializable
data class FileContent(
    val path: String = "",
    val encoding: String = "utf-8",
    val bom: Boolean = false,
    val text: String? = null,
    val sha256: String = "",
    val size: Long = 0,
    val truncated: Boolean = false,
    val modifiedMs: Long? = null,
    val lineEnding: String = "lf",
    val sensitive: Boolean = false,
    val language: String? = null,
)

@Serializable
data class WriteBody(
    val commandId: String,
    val path: String,
    val text: String,
    /** SHA-256 the edit is based on; null = create a new file. */
    val baseSha256: String?,
    val encoding: String? = null,
    val bom: Boolean = false,
    val createOnly: Boolean = false,
)

@Serializable
data class PathBody(val commandId: String, val path: String)

@Serializable
data class RenameBody(val commandId: String, val from: String, val to: String, val overwrite: Boolean = false)

@Serializable
data class BeginUploadBody(val commandId: String, val path: String, val size: Long, val overwrite: Boolean = false)

@Serializable
data class UploadView(val uploadId: String = "", val path: String = "", val size: Long = 0, val received: Long = 0, val complete: Boolean = false)

data class SearchHit(val path: String, val name: String, val line: Int?, val snippet: String?) {
    companion object {
        fun parseList(o: JsonObject?): List<SearchHit> = o?.get("results").listOfObjects().map {
            SearchHit(it.str("path") ?: "", it.str("name") ?: it.str("path")?.substringAfterLast('\\')?.substringAfterLast('/') ?: "", it.long("line")?.toInt(), it.str("snippet", "text", "preview"))
        }.orEmpty()
    }
}

@Serializable
data class GitStatusEntry(
    val path: String = "",
    val origPath: String? = null,
    val index: String = " ",
    val worktree: String = " ",
    val untracked: Boolean = false,
    val conflicted: Boolean = false,
)

@Serializable
data class RepoStatus(
    val root: String = "",
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Long = 0,
    val behind: Long = 0,
    val entries: List<GitStatusEntry> = emptyList(),
)

@Serializable
data class GitDiff(val root: String = "", val diff: String = "", val truncated: Boolean = false)

@Serializable
data class GitCommit(val hash: String = "", val author: String = "", val email: String = "", val timeMs: Long = 0, val subject: String = "")

@Serializable
data class GitLog(val commits: List<GitCommit> = emptyList())

@Serializable
data class GitShow(val text: String? = null, val binary: Boolean = false)

// ---------------------------------------------------------------------------------------------
// §7.11 Port forwarding
// ---------------------------------------------------------------------------------------------

/** A TCP port listening on the desktop's loopback; [process] where the desktop OS tells (Windows). */
@Serializable
data class ListeningPort(val port: Int, val process: String? = null, val pid: Long? = null)

@Serializable
data class ListeningPorts(val ports: List<ListeningPort> = emptyList())

/** A forward lease. [listening] is only set in the response of `POST /v1/forwards`. */
@Serializable
data class ForwardView(
    val id: String,
    val port: Int,
    val createdAtMs: Long = 0,
    val expiresAtMs: Long = 0,
    val connected: Boolean = false,
    val activeStreams: Int = 0,
    val totalStreams: Long = 0,
    val bytesDown: Long = 0,
    val bytesUp: Long = 0,
    val listening: Boolean? = null,
)

@Serializable
data class ForwardList(val forwards: List<ForwardView> = emptyList())

@Serializable
data class CreateForwardBody(val port: Int, val ttlMinutes: Int? = null)
