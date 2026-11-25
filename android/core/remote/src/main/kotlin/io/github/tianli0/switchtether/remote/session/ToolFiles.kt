package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.arr
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonObject

/**
 * Display helpers for tool calls about files: Claude Code's Read / Edit / Write (the file is in
 * `input`), and steps the desktop names the files of in `data.files` (`[{path, offset?, limit?}]`:
 * a Codex shell command that reads files, a Codex patch).
 */
object ToolFiles {
    private val FILE_CATEGORIES = setOf("read", "edit", "write")

    /** One file a step is about, with the part of it a read asked for (`L120–169`, `p. 3`; null = whole file). */
    data class FileRef(val path: String, val range: String?)

    /** The files a step is about, as the agent wrote them (may be relative). */
    fun files(data: JsonObject): List<FileRef> {
        val listed = data["files"].arr()?.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            clean(o.str("path"))?.let { FileRef(it, rangeOf(o)) }
        }.orEmpty()
        if (listed.isNotEmpty()) return listed.distinctBy { it.path }
        return listOfNotNull(inputPath(data)?.let { FileRef(it, range(data)) })
    }

    /** The one file a step is about; null when it is about none or several. */
    fun path(data: JsonObject): String? = files(data).singleOrNull()?.path

    private fun inputPath(data: JsonObject): String? {
        if (data.str("category") !in FILE_CATEGORIES) return null
        val input = data["input"].obj() ?: return null
        return clean(input.str("file_path", "path", "notebook_path", "file"))
    }

    private fun clean(p: String?): String? = p?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }

    /** File name of [path] (either separator). */
    fun name(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /** Folder part of [path], empty for a bare file name. */
    fun folder(path: String): String = path.trimEnd('/', '\\').let { it.dropLast(name(it).length).trimEnd('/', '\\') }

    /** The part of the file a read asked for: `L120–169`, `L120+`, `p. 3-5`; null for the whole file. */
    fun range(data: JsonObject): String? {
        if (data["files"].arr()?.isNotEmpty() == true) return files(data).singleOrNull()?.range
        return rangeOf(data["input"].obj() ?: return null)
    }

    private fun rangeOf(o: JsonObject): String? {
        o.str("pages")?.takeIf { it.isNotBlank() }?.let { return "p. $it" }
        val offset = o.long("offset")?.takeIf { it > 0 }
        val limit = o.long("limit")?.takeIf { it > 0 }
        return when {
            offset != null && limit != null -> if (limit == 1L) "L$offset" else "L$offset–${offset + limit - 1}"
            offset != null -> "L$offset+"
            limit != null -> "L1–$limit"
            else -> null
        }
    }
}
