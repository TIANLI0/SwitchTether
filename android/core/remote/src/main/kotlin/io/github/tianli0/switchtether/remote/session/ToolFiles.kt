package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import kotlinx.serialization.json.JsonObject

/** Display helpers for tool calls that work on one file (Read / Edit / Write and their kin). */
object ToolFiles {
    private val FILE_CATEGORIES = setOf("read", "edit", "write")

    /** The file a read / edit / write call is about, as the agent wrote it (may be relative). */
    fun path(data: JsonObject): String? {
        if (data.str("category") !in FILE_CATEGORIES) return null
        val input = data["input"].obj() ?: return null
        return input.str("file_path", "path", "notebook_path", "file")?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }
    }

    /** File name of [path] (either separator). */
    fun name(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /** Folder part of [path], empty for a bare file name. */
    fun folder(path: String): String = path.trimEnd('/', '\\').let { it.dropLast(name(it).length).trimEnd('/', '\\') }

    /** The part of the file a read asked for: `L120–169`, `L120+`, `p. 3-5`; null for the whole file. */
    fun range(data: JsonObject): String? {
        val input = data["input"].obj() ?: return null
        input.str("pages")?.takeIf { it.isNotBlank() }?.let { return "p. $it" }
        val offset = input.long("offset")?.takeIf { it > 0 }
        val limit = input.long("limit")?.takeIf { it > 0 }
        return when {
            offset != null && limit != null -> "L$offset–${offset + limit - 1}"
            offset != null -> "L$offset+"
            limit != null -> "L1–$limit"
            else -> null
        }
    }
}
