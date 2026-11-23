package io.github.tianli0.switchtether.data

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Last known session list and the newest events of recently opened conversations, kept on the
 * phone so a fresh start shows the previous state at once ("上次的记录") while the connection is
 * being set up. The desktop stays the source of truth: everything here is replaced by the first
 * successful load. App-private storage only (`filesDir/session-cache`); cleared on unpair.
 */
class SessionCache(private val dir: File, private val maxConversations: Int = 40) {
    @Serializable
    private data class CachedEvents(val events: List<AgentEvent>, val hasMoreBefore: Boolean, val savedAtMs: Long)

    private val lock = Any()

    private fun listFile(serverId: String) = File(dir, "${safe(serverId)}/sessions.json")
    private fun eventsFile(serverId: String, sessionId: String) = File(dir, "${safe(serverId)}/events/${safe(sessionId)}.json")

    fun loadList(serverId: String): List<SessionSummary>? = synchronized(lock) {
        runCatching {
            listFile(serverId).takeIf { it.isFile }?.readText()
                ?.let { ProtocolJson.decodeFromString(ListSerializer(SessionSummary.serializer()), it) }
        }.getOrNull()
    }

    fun saveList(serverId: String, items: List<SessionSummary>) = synchronized(lock) {
        runCatching { write(listFile(serverId), ProtocolJson.encodeToString(ListSerializer(SessionSummary.serializer()), items.take(200))) }
        Unit
    }

    /** Newest events of a conversation and whether older ones exist on the desktop. */
    fun loadEvents(serverId: String, sessionId: String): Pair<List<AgentEvent>, Boolean>? = synchronized(lock) {
        runCatching {
            eventsFile(serverId, sessionId).takeIf { it.isFile }?.readText()
                ?.let { ProtocolJson.decodeFromString(CachedEvents.serializer(), it) }
                ?.let { it.events to it.hasMoreBefore }
        }.getOrNull()
    }

    fun saveEvents(serverId: String, sessionId: String, events: List<AgentEvent>, hasMoreBefore: Boolean) = synchronized(lock) {
        runCatching {
            // The newest page is enough to show where the conversation stands.
            val keep = events.sortedBy { it.seq }.takeLast(MAX_EVENTS)
            val more = hasMoreBefore || keep.size < events.size
            write(eventsFile(serverId, sessionId), ProtocolJson.encodeToString(CachedEvents.serializer(), CachedEvents(keep, more, System.currentTimeMillis())))
            prune(File(dir, "${safe(serverId)}/events"))
        }
        Unit
    }

    fun clear() = synchronized(lock) {
        dir.deleteRecursively()
        Unit
    }

    private fun prune(eventsDir: File) {
        val files = eventsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxConversations).forEach { it.delete() }
    }

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val MAX_EVENTS = 300

        /** File-name safe form of an id (ids contain `:`). */
        internal fun safe(id: String): String =
            id.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("").take(120) +
                "_" + Integer.toHexString(id.hashCode())
    }
}
