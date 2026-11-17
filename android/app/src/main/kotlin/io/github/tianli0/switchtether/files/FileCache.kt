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
    class Pending internal constructor(val key: String, val name: String, val file: File)

    /**
     * Returns the cached file for [key], or null. With [maxAgeMs] set, an older copy counts as a miss
     * (used when the remote size/mtime are unknown, so a stale copy cannot be trusted forever).
     */
    @Synchronized
    fun get(key: String, maxAgeMs: Long? = null): File? {
        val entryDir = File(dir, key)
        val file = entryDir.listFiles()?.firstOrNull { it.isFile } ?: return null
        if (maxAgeMs != null && clock() - file.lastModified() > maxAgeMs) {
            entryDir.deleteRecursively()
            return null
        }
        entryDir.setLastModified(clock())
        return file
    }

    @Synchronized
    fun begin(key: String, displayName: String): Pending {
        dir.mkdirs()
        val tmp = File(dir, "$TMP_PREFIX$key")
        tmp.delete()
        return Pending(key, FileTypes.safeFileName(displayName), tmp)
    }

    /** Moves the finished temp file into place, then evicts above the cap (never the file just added). */
    @Synchronized
    fun commit(p: Pending): File {
        val entryDir = File(dir, p.key)
        entryDir.deleteRecursively()
        entryDir.mkdirs()
        val dest = File(entryDir, p.name)
        check(p.file.renameTo(dest)) { "cannot move into cache" }
        dest.setLastModified(clock())
        entryDir.setLastModified(clock())
        evict(keep = p.key)
        return dest
    }

    @Synchronized
    fun abort(p: Pending) {
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
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private class Entry(val key: String, val size: Long, val lastUsed: Long)

    private fun entries(): List<Entry> = dir.listFiles().orEmpty().filter { it.isDirectory }.map { d ->
        Entry(d.name, d.walkTopDown().filter { it.isFile }.sumOf { it.length() }, d.lastModified())
    }

    private fun tmpFiles(): List<File> = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(TMP_PREFIX) }

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        private const val TMP_PREFIX = ".part-"

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
