package io.github.tianli0.switchtether.remote.session

import io.github.tianli0.switchtether.remote.protocol.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Display helpers only. Original command/input is retained for expansion and copying. */
object ToolCommands {
    private val powershell = Regex("""(?is)^\s*["']?(?:[^\r\n"']*[\\/])?(?:powershell|pwsh)(?:\.exe)?["']?\s+(?:-(?:NoProfile|NoLogo|NonInteractive)\s+|-ExecutionPolicy\s+\S+\s+)*-(?:Command|c)\s+(.+)$""")
    private val posix = Regex("""(?is)^\s*["']?(?:[^\r\n"']*/)?(?:bash|zsh|sh)["']?\s+-(?:lc|c)\s+(.+)$""")

    fun raw(data: JsonObject): String? {
        val input = data["input"].obj() ?: return null
        val command = input["command"] ?: input["cmd"] ?: return null
        return when (command) {
            is JsonPrimitive -> command.content
            is JsonArray -> command.joinToString(" ") { (it as? JsonPrimitive)?.content ?: it.toString() }
            else -> null
        }
    }

    fun preview(command: String): String {
        val body = powershell.matchEntire(command)?.groupValues?.get(1)
            ?: posix.matchEntire(command)?.groupValues?.get(1) ?: return command.trim()
        val trimmed = body.trim()
        return if (trimmed.length >= 2 && trimmed.first() == trimmed.last() && trimmed.first() in "\"'") trimmed.substring(1, trimmed.length - 1) else trimmed
    }
}
