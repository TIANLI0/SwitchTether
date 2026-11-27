package io.github.tianli0.switchtether.data

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.security.BlobCipher
import java.io.File
import kotlinx.serialization.Serializable

/** The last session list seen from one computer and when it was read from it. */
data class ListSnapshot(val items: List<SessionSummary>, val savedAtMs: Long)

/** The newest events of one conversation as last seen, and whether older ones exist on the computer. */
data class EventsSnapshot(val events: List<AgentEvent>, val hasMoreBefore: Boolean, val savedAtMs: Long)

/**
 * Offline snapshot of what the phone last saw from a paired computer, shown while the computer is
 * asleep / unreachable and before the first connection of a fresh start ("离线快照 · 10:42"). The
 * computer stays the source of truth: the first successful load replaces everything here.
 *
 * What is kept where (SECURITY.md §5: the app stores no transcripts persistently):
 * - **Session list metadata** (ids, titles, folders, state, times; at most [maxListItems] rows and
 *   [MAX_LIST_BYTES]) is persisted per computer in `filesDir/session-cache/<server>/sessions.bin`,
 *   sealed with [cipher] (a non-exportable Keystore AES-GCM key in the app) and bound to the
 *   computer's id. Without a cipher nothing is written.
 * - **Conversation events** (transcripts, tool output) live in memory only, for the life of the
 *   process: at most [maxConversations] conversations, [maxEventsPerConversation] events each and
 *   [maxBytes] in total (JSON size), least recently used evicted first.
 *
 * Everything is keyed by the computer's server id, so two computers never mix. [clear] (unpair,
 * revoke, "clear caches") drops memory, deletes the files and destroys the key. Earlier versions
 * wrote conversations to disk in plain text; those files are deleted on first use.
 */
class SessionCache(
    private val dir: File,
    private val cipher: BlobCipher?,
    private val maxConversations: Int = 40,
    private val maxEventsPerConversation: Int = MAX_EVENTS,
    private val maxBytes: Long = MAX_BYTES,
    private val maxListItems: Int = 200,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class StoredList(val savedAtMs: Long, val items: List<SessionSummary>)

    private data class Key(val serverId: String, val sessionId: String)

    private class Entry(val snapshot: EventsSnapshot, val bytes: Long)

    private val lock = Any()
    private var legacyChecked = false
    private val lists = HashMap<String, ListSnapshot>()

    /** Access-ordered: iteration starts at the least recently used conversation. */
    private val conversations = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var totalBytes = 0L

    private fun serverDir(serverId: String) = File(dir, safe(serverId))
    private fun listFile(serverId: String) = File(serverDir(serverId), "sessions.bin")
    private fun aad(serverId: String) = "switchtether-session-list:v1:$serverId".toByteArray(Charsets.UTF_8)

    fun loadList(serverId: String): ListSnapshot? = synchronized(lock) {
        dropLegacy()
        lists[serverId]?.let { return it }
        val c = cipher ?: return null
        val snap = runCatching {
            val sealed = listFile(serverId).takeIf { it.isFile }?.readBytes() ?: return null
            val plain = c.open(sealed, aad(serverId)) ?: return null
            ProtocolJson.decodeFromString(StoredList.serializer(), plain.toString(Charsets.UTF_8))
        }.getOrNull()?.let { ListSnapshot(it.items, it.savedAtMs) }
        snap?.also { lists[serverId] = it }
    }

    fun saveList(serverId: String, items: List<SessionSummary>) = synchronized(lock) {
        dropLegacy()
        var keep = items.take(maxListItems)
        var json = encodeList(keep)
        // A list too large for the budget keeps its newest rows (the desktop sends them first).
        while (json.length > MAX_LIST_BYTES && keep.isNotEmpty()) {
            keep = keep.take(keep.size * 3 / 4)
            json = encodeList(keep)
        }
        val snap = ListSnapshot(keep, now())
        lists[serverId] = snap
        val c = cipher ?: return@synchronized
        runCatching {
            val sealed = c.seal(ProtocolJson.encodeToString(StoredList.serializer(), StoredList(snap.savedAtMs, keep)).toByteArray(Charsets.UTF_8), aad(serverId))
            if (sealed != null) write(listFile(serverId), sealed) else listFile(serverId).delete()
        }
        Unit
    }

    /** Newest events of a conversation as last seen (memory only). */
    fun loadEvents(serverId: String, sessionId: String): EventsSnapshot? = synchronized(lock) {
        conversations[Key(serverId, sessionId)]?.snapshot
    }

    fun saveEvents(serverId: String, sessionId: String, events: List<AgentEvent>, hasMoreBefore: Boolean) = synchronized(lock) {
        val key = Key(serverId, sessionId)
        conversations.remove(key)?.let { totalBytes -= it.bytes }
        if (events.isEmpty() || maxConversations <= 0) return@synchronized
        // The newest page is enough to show where the conversation stands.
        var keep = events.sortedBy { it.seq }.takeLast(maxEventsPerConversation)
        val sizes = keep.map { sizeOf(it) }.toMutableList()
        var bytes = sizes.sum()
        // One conversation alone may not take more than the whole budget: its oldest events go.
        var dropped = 0
        while (bytes > maxBytes && dropped < keep.size) bytes -= sizes[dropped++]
        if (dropped > 0) keep = keep.drop(dropped)
        if (keep.isEmpty()) return@synchronized
        val more = hasMoreBefore || keep.size < events.size
        conversations[key] = Entry(EventsSnapshot(keep, more, now()), bytes)
        totalBytes += bytes
        evict()
    }

    /** Drops everything kept for one computer (it was replaced by another pairing). */
    fun forget(serverId: String) = synchronized(lock) {
        lists.remove(serverId)
        val it = conversations.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.serverId == serverId) {
                totalBytes -= e.value.bytes
                it.remove()
            }
        }
        serverDir(serverId).deleteRecursively()
        Unit
    }

    /** Unpair / revoke / "clear caches": memory, files and the key. */
    fun clear() = synchronized(lock) {
        lists.clear()
        conversations.clear()
        totalBytes = 0
        dir.deleteRecursively()
        cipher?.destroy()
        Unit
    }

    /** Conversations kept in memory right now (tests). */
    internal fun conversationCount(): Int = synchronized(lock) { conversations.size }

    internal fun bytesInMemory(): Long = synchronized(lock) { totalBytes }

    private fun evict() {
        val it = conversations.entries.iterator()
        while ((conversations.size > maxConversations || totalBytes > maxBytes) && it.hasNext()) {
            val e = it.next()
            totalBytes -= e.value.bytes
            it.remove()
        }
    }

    /** Plain-text files of earlier versions (`sessions.json`, `events/`): transcripts must not stay on disk. */
    private fun dropLegacy() {
        if (legacyChecked) return
        legacyChecked = true
        dir.listFiles()?.filter { it.isDirectory }?.forEach { server ->
            File(server, "events").deleteRecursively()
            File(server, "sessions.json").delete()
        }
    }

    private fun encodeList(items: List<SessionSummary>) =
        ProtocolJson.encodeToString(StoredList.serializer(), StoredList(0, items))

    private fun sizeOf(e: AgentEvent): Long = runCatching { ProtocolJson.encodeToString(AgentEvent.serializer(), e).length.toLong() }.getOrDefault(1024L)

    private fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val MAX_EVENTS = 300

        /** All conversations together (JSON characters). */
        const val MAX_BYTES = 8L * 1024 * 1024

        /** The persisted list (JSON characters, before sealing). */
        const val MAX_LIST_BYTES = 512 * 1024

        /** File-name safe form of an id (ids contain `:`). */
        internal fun safe(id: String): String =
            id.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("").take(120) +
                "_" + Integer.toHexString(id.hashCode())
    }
}
