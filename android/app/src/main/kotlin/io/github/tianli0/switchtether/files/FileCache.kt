package io.github.tianli0.switchtether.files

import java.io.File
import java.security.MessageDigest

/**
 * The CACHE half of the storage design (docs/ANDROID_UI.md "Downloads vs cache"): temporary local
 * copies made only so a file can be previewed or handed to another app. Lives in `cacheDir`, is never
 * shown to the user as a download, is evicted least-recently-used above [maxBytes], and is wiped by
 * "Clear cache", unpair and forget-server.
 *
 * Layout: `<dir>/<key>/<display name>`; an entry's LRU time is its directory's mtime (touched on every
 * hit), its age is the file's own mtime (set once, when committed).
 */
class FileCache(
    private val dir: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A copy under construction; not visible to [get] until [commit]. */
    class Pending internal constructor(val key: String, val name: String, val file: File, val source: String?)

    /**
     * One committed copy, for the "recently previewed" list: [source] is the remote path it was made
     * from (null for copies made before sources were recorded, or for in-memory bytes such as a chat image).
     */
    data class Cached(val key: String, val file: File, val source: String?, val lastUsedMs: Long) {
        val name: String get() = file.name
        val size: Long get() = file.length()
    }

    /**
     * Returns the cached file for [key], or null. With [maxAgeMs] set, an older copy counts as a miss
     * (used when the remote size/mtime are unknown, so a stale copy cannot be trusted forever).
     */
    @Synchronized
    fun get(key: String, maxAgeMs: Long? = null): File? {
        val entryDir = File(dir, key)
        val file = contentOf(entryDir) ?: return null
        if (maxAgeMs != null && clock() - file.lastModified() > maxAgeMs) {
            entryDir.deleteRecursively()
            return null
        }
        entryDir.setLastModified(clock())
        return file
    }

    @Synchronized
    fun begin(key: String, displayName: String, source: String? = null): Pending {
        dir.mkdirs()
        // Leftovers of this key from a crashed run (no live download owns them) are swept here; the temp
        // files of downloads still in flight are never touched.
        dir.listFiles().orEmpty()
            .filter { it.isFile && (it.name == "$TMP_PREFIX$key" || it.name.startsWith("$TMP_PREFIX$key-")) && it.name !in live }
            .forEach { it.delete() }
        // One temp file per download: two concurrent downloads of the same file never share bytes.
        val tmp = File(dir, "$TMP_PREFIX$key-${seq.incrementAndGet()}-${java.util.UUID.randomUUID()}")
        tmp.delete()
        live += tmp.name
        return Pending(key, FileTypes.safeFileName(displayName), tmp, source)
    }

    /** Moves the finished temp file into place, then evicts above the cap (never the file just added). */
    @Synchronized
    fun commit(p: Pending): File {
        live -= p.file.name
        check(p.file.isFile) { "nothing to move into cache" }
        val entryDir = File(dir, p.key)
        entryDir.deleteRecursively()
        entryDir.mkdirs()
        val dest = File(entryDir, p.name)
        if (!p.file.renameTo(dest)) {
            p.file.delete()
            error("cannot move into cache")
        }
        dest.setLastModified(clock())
        if (p.source != null) {
            File(entryDir, SOURCE_FILE).writeText(p.source)
            // An older copy of the same remote file (it changed on the computer since) is dead weight:
            // nothing looks it up again, and "recently previewed" must not list the file twice.
            entries().filter { it.key != p.key && sourceOf(File(dir, it.key)) == p.source }.forEach { File(dir, it.key).deleteRecursively() }
        }
        entryDir.setLastModified(clock())
        evict(keep = p.key)
        return dest
    }

    /** Committed copies, most recently used first. */
    @Synchronized
    fun list(): List<Cached> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
        contentOf(d)?.let { Cached(d.name, it, sourceOf(d), d.lastModified()) }
    }.sortedByDescending { it.lastUsedMs }

    /** The newest copy made from [source], whatever its size / time: the offline fallback. */
    @Synchronized
    fun latestFor(source: String): Cached? = list().firstOrNull { it.source == source }

    @Synchronized
    fun remove(key: String) {
        File(dir, key).deleteRecursively()
    }

    @Synchronized
    fun abort(p: Pending) {
        live -= p.file.name
        p.file.delete()
    }

    @Synchronized
    fun sizeBytes(): Long = entries().sumOf { it.size } + tmpFiles().sumOf { it.length() }

    /** Deletes least-recently-used entries until the total is within [maxBytes]. */
    @Synchronized
    fun evict(keep: String? = null) {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (e in entries().filter { it.key != keep }.sortedBy { it.lastUsed }) {
            if (total <= maxBytes) break
            File(dir, e.key).deleteRecursively()
            total -= e.size
        }
    }

    @Synchronized
    fun clear() {
        live.clear()
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Temp file names owned by a download of THIS process that is still in flight (guarded by the monitor). */
    private val live = HashSet<String>()
    private val seq = java.util.concurrent.atomic.AtomicLong()

    private class Entry(val key: String, val size: Long, val lastUsed: Long)

    private fun entries(): List<Entry> = dir.listFiles().orEmpty().filter { it.isDirectory }.map { d ->
        Entry(d.name, d.walkTopDown().filter { it.isFile }.sumOf { it.length() }, d.lastModified())
    }

    /** The cached file of an entry directory (the source note beside it is hidden). */
    private fun contentOf(entryDir: File): File? = entryDir.listFiles()?.firstOrNull { it.isFile && it.name != SOURCE_FILE }

    private fun sourceOf(entryDir: File): String? = File(entryDir, SOURCE_FILE).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    private fun tmpFiles(): List<File> = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(TMP_PREFIX) }

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        private const val TMP_PREFIX = ".part-"

        /** Beside each copy: the remote path it came from (a cached name is only the file name). */
        private const val SOURCE_FILE = ".source"

        /**
         * Cache identity of a remote file: same server + path + size + modified time ⇒ same bytes.
         * Unknown size/mtime are part of the key as "?", so a copy made without them is never confused
         * with one made with them (and is additionally bounded by a max age at lookup).
         */
        fun keyFor(serverId: String, path: String, size: Long?, modifiedMs: Long?): String {
            val raw = "$serverId\n$path\n${size ?: "?"}\n${modifiedMs ?: "?"}"
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}
