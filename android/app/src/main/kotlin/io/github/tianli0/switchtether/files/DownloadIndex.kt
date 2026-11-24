package io.github.tianli0.switchtether.files

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One file the user explicitly downloaded. The bytes live in the public Downloads collection
 * (`Downloads/SwitchTether`, via MediaStore); this record is how the app knows which entries are its own.
 */
@Serializable
data class DownloadRecord(
    val id: String,
    val serverId: String,
    val remotePath: String,
    val name: String,
    val size: Long,
    val mime: String,
    /** MediaStore content URI string. */
    val uri: String,
    val savedAtMs: Long,
) {
    /**
     * Whether this copy can still stand in for the remote file as a listing describes it now: the same
     * size, and not changed on the computer after the copy was made (a same-size rewrite is otherwise
     * indistinguishable). Unknown values do not disqualify.
     */
    fun matchesRemote(size: Long?, modifiedMs: Long?): Boolean =
        (size == null || this.size == size) && (modifiedMs == null || modifiedMs <= 0 || modifiedMs <= savedAtMs)
}

/**
 * The DOWNLOAD half of the storage design: a small JSON index in `filesDir` (never sent anywhere,
 * contains no secrets). Only records in this index are ever deleted from MediaStore.
 */
class DownloadIndex(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded: List<DownloadRecord>? = null

    @Synchronized
    fun list(): List<DownloadRecord> = load().sortedByDescending { it.savedAtMs }

    @Synchronized
    fun add(r: DownloadRecord) = save(load().filter { it.id != r.id } + r)

    @Synchronized
    fun remove(ids: Set<String>) = save(load().filter { it.id !in ids })

    @Synchronized
    fun clear() = save(emptyList())

    /** Records of this remote file, newest first. */
    @Synchronized
    fun find(serverId: String, remotePath: String): List<DownloadRecord> =
        load().filter { it.serverId == serverId && it.remotePath == remotePath }.sortedByDescending { it.savedAtMs }

    /** Drops records whose MediaStore row no longer exists (the user deleted the file elsewhere); returns them. */
    @Synchronized
    fun prune(exists: (DownloadRecord) -> Boolean): List<DownloadRecord> {
        val (keep, gone) = load().partition(exists)
        if (gone.isNotEmpty()) save(keep)
        return gone
    }

    private fun load(): List<DownloadRecord> = loaded ?: runCatching {
        if (file.exists()) json.decodeFromString(ListSerializer(DownloadRecord.serializer()), file.readText()) else emptyList()
    }.getOrDefault(emptyList()).also { loaded = it }

    private fun save(records: List<DownloadRecord>) {
        loaded = records
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(DownloadRecord.serializer()), records))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
