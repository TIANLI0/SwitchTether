package io.github.tianli0.switchtether.files

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.tianli0.switchtether.remote.api.RemoteApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A file on this phone that a viewer or another app can read: a cache copy or a user download. */
data class LocalFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    /** True when this is the user's explicit download (not a temporary cache copy). */
    val fromDownload: Boolean,
)

class FileTooLargeException(val limitBytes: Long) : IOException("file exceeds the preview limit of $limitBytes bytes")

class NotConnectedForFilesException : IOException("not connected to the computer")

/**
 * All local file storage of the app, split in two on purpose (see docs/ANDROID_UI.md "Downloads vs cache"):
 *
 * - **Cache** ([FileCache], `cacheDir/remote-files`): temporary copies for preview / "Open with…".
 *   Automatic, size-capped, never listed as a download, wiped on "Clear cache" and unpair.
 * - **Downloads** ([DownloadIndex] + MediaStore `Downloads/SwitchTether`): files the user chose to keep.
 *   They survive cache clearing and unpair, and are managed (opened, shared, deleted) in one screen.
 *
 * Sensitive files are never downloaded by the desktop (`fs/download` refuses them), so neither store can
 * ever hold one.
 */
class FileStore(
    private val context: Context,
    private val serverId: () -> String?,
    private val api: () -> RemoteApi?,
    val cache: FileCache = FileCache(File(context.cacheDir, "remote-files")),
    val index: DownloadIndex = DownloadIndex(File(context.filesDir, "downloads-index.json")),
) {
    private val resolver: ContentResolver get() = context.contentResolver

    private val _downloads = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val downloads: StateFlow<List<DownloadRecord>> = _downloads.asStateFlow()
    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    /** Prunes downloads whose file was removed elsewhere, then republishes both sizes. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        index.prune { exists(Uri.parse(it.uri)) }
        _downloads.value = index.list()
        _cacheBytes.value = cache.sizeBytes()
    }

    private fun publish() {
        _downloads.value = index.list()
        _cacheBytes.value = cache.sizeBytes()
    }

    // ---- open / preview (cache) ----------------------------------------------------------------------

    /** Size and modified time of a file on the computer, as `fs/list` reports them. */
    data class RemoteMeta(val size: Long?, val modifiedMs: Long?)

    /**
     * Asks the computer for [path]'s current size and modified time (one `fs/list` of its folder), or
     * null when that is not possible (offline, folder blocked, file gone). A link from chat carries
     * neither, and without them no local copy can be told fresh from stale.
     */
    suspend fun statRemote(path: String): RemoteMeta? {
        val a = api() ?: return null
        val parent = FileTypes.parentOf(path) ?: return null
        val listing = withTimeoutOrNull(STAT_TIMEOUT_MS) { runCatching { a.fsList(parent, showHidden = true) }.getOrNull() } ?: return null
        return metaIn(listing, path)
    }

    /**
     * Makes [path] readable locally for viewing. Order: an existing user download of the same file ->
     * a fresh cache copy -> fetch into the cache. Size and modified time decide what is fresh; when the
     * caller has neither (a link from chat) they are asked from the computer first, so a file that
     * changed there is fetched again instead of showing the copy from last time. Offline, the newest
     * local copy of that path is shown rather than nothing.
     */
    suspend fun resolve(
        path: String,
        name: String = FileTypes.fileName(path),
        size: Long? = null,
        modifiedMs: Long? = null,
        forceRemote: Boolean = false,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): LocalFile = withContext(Dispatchers.IO) {
        val server = serverId() ?: throw NotConnectedForFilesException()
        var knownSize = size?.takeIf { it > 0 }
        var knownTime = modifiedMs?.takeIf { it > 0 }
        if (knownSize == null && knownTime == null && !forceRemote) {
            val meta = statRemote(path)
            if (meta != null) {
                knownSize = meta.size
                knownTime = meta.modifiedMs
            } else if (api() == null) {
                // Not connected: the newest copy on the phone is better than an error.
                index.find(server, path).firstOrNull { exists(Uri.parse(it.uri)) }?.let { return@withContext asLocal(it) }
                cache.latestFor(path)?.let { return@withContext it.file.asLocal(name) }
                throw NotConnectedForFilesException()
            }
        }
        if (!forceRemote) {
            index.find(server, path).firstOrNull { it.matchesRemote(knownSize, knownTime) && exists(Uri.parse(it.uri)) }
                ?.let { return@withContext asLocal(it) }
        }
        val key = FileCache.keyFor(server, path, knownSize, knownTime)
        val unknownMeta = knownSize == null && knownTime == null
        if (!forceRemote) {
            cache.get(key, if (unknownMeta) UNKNOWN_META_MAX_AGE_MS else null)?.let { return@withContext it.asLocal(name) }
        }
        val limit = knownSize
        if (limit != null && limit > cache.maxBytes) throw FileTooLargeException(cache.maxBytes)
        val a = api() ?: throw NotConnectedForFilesException()
        val pending = cache.begin(key, name, source = path)
        try {
            FileOutputStream(pending.file).use { out ->
                a.download(path, out) { n, total ->
                    if (n > cache.maxBytes) throw FileTooLargeException(cache.maxBytes)
                    onProgress(n, total)
                }
            }
            cache.commit(pending).asLocal(name).also { _cacheBytes.value = cache.sizeBytes() }
        } catch (t: Throwable) {
            cache.abort(pending)
            throw t
        }
    }

    private fun File.asLocal(displayName: String = this.name): LocalFile = LocalFile(
        uri = FileProvider.getUriForFile(context, authority(), this),
        name = displayName,
        mime = mimeOf(displayName),
        size = length(),
        fromDownload = false,
    )

    /** Recently previewed copies (the cache), most recently used first. */
    suspend fun cachedFiles(): List<FileCache.Cached> = withContext(Dispatchers.IO) { cache.list() }

    fun asLocal(c: FileCache.Cached): LocalFile = c.file.asLocal(c.name)

    suspend fun removeCached(key: String) = withContext(Dispatchers.IO) {
        cache.remove(key)
        publish()
    }

    /**
     * Bytes the app already holds (a picture in a chat message) as a temporary copy another app can
     * open or receive: same cache, keyed by content, so sharing twice writes once.
     */
    suspend fun cacheBytes(name: String, bytes: ByteArray): LocalFile = withContext(Dispatchers.IO) {
        val key = "bytes-" + FileCache.keyFor("bytes", sha256(bytes), bytes.size.toLong(), null).take(32)
        cache.get(key)?.let { return@withContext it.asLocal(name) }
        val pending = cache.begin(key, name)
        try {
            pending.file.writeBytes(bytes)
            cache.commit(pending).asLocal(name).also { _cacheBytes.value = cache.sizeBytes() }
        } catch (t: Throwable) {
            cache.abort(pending)
            throw t
        }
    }

    /** Keeps bytes the app holds (a chat picture with no file on the computer) in `Downloads/SwitchTether`. */
    suspend fun saveBytes(name: String, bytes: ByteArray): DownloadRecord = withContext(Dispatchers.IO) {
        writeDownload(name, serverId().orEmpty(), remotePath = "") { out -> out.write(bytes); bytes.size.toLong() }
    }

    /** Keeps a previewed copy: copied from the cache into `Downloads/SwitchTether` (no transfer). */
    suspend fun saveCached(c: FileCache.Cached): DownloadRecord = withContext(Dispatchers.IO) {
        writeDownload(c.name, serverId().orEmpty(), remotePath = c.source.orEmpty()) { out -> c.file.inputStream().use { it.copyTo(out) } }
    }

    // ---- explicit download ---------------------------------------------------------------------------

    /**
     * The preview copy of [path] when it is still good: the same rules as [resolve] (same size and
     * modified time, or made without them less than [UNKNOWN_META_MAX_AGE_MS] ago).
     */
    private fun cachedCopy(server: String, path: String, size: Long?, modifiedMs: Long?): File? {
        val unknown = size == null && modifiedMs == null
        cache.get(FileCache.keyFor(server, path, size, modifiedMs), if (unknown) UNKNOWN_META_MAX_AGE_MS else null)?.let { return it }
        if (unknown) return null
        // Opened from a link (no size / time known then) and now kept from a listing that has them.
        return cache.get(FileCache.keyFor(server, path, null, null), UNKNOWN_META_MAX_AGE_MS)?.takeIf { size == null || it.length() == size }
    }

    /**
     * Saves [path] into `Downloads/SwitchTether` and records it. A file that is already on the phone
     * as a fresh preview copy is copied from there (no second transfer from the computer, works
     * offline); otherwise it is fetched. A cancelled or failed download leaves nothing behind.
     */
    suspend fun download(
        path: String,
        name: String = FileTypes.fileName(path),
        size: Long? = null,
        modifiedMs: Long? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val server = serverId() ?: throw NotConnectedForFilesException()
        var knownSize = size?.takeIf { it > 0 }
        var knownTime = modifiedMs?.takeIf { it > 0 }
        if (knownSize == null && knownTime == null) {
            statRemote(path)?.let {
                knownSize = it.size
                knownTime = it.modifiedMs
            }
        }
        val cached = cachedCopy(server, path, knownSize, knownTime)
        val a = if (cached == null) api() ?: throw NotConnectedForFilesException() else null
        writeDownload(name, server, path) { out ->
            if (cached != null) {
                val total = cached.length()
                var done = 0L
                cached.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
                done
            } else {
                a!!.download(path, out, onProgress)
            }
        }
    }

    /** One new entry in `Downloads/SwitchTether`, filled by [write], recorded in the index; nothing is left behind on failure. */
    private suspend fun writeDownload(name: String, server: String, remotePath: String, write: suspend (java.io.OutputStream) -> Long): DownloadRecord {
        val safe = FileTypes.safeFileName(name)
        val mime = mimeOf(safe)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("cannot create download")
        try {
            val written = (resolver.openOutputStream(uri) ?: throw IOException("cannot write download")).use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val record = DownloadRecord(
                id = UUID.randomUUID().toString(), serverId = server, remotePath = remotePath, name = displayNameOf(uri) ?: safe,
                size = written, mime = mime, uri = uri.toString(), savedAtMs = System.currentTimeMillis(),
            )
            index.add(record)
            publish()
            return record
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun asLocal(r: DownloadRecord): LocalFile = LocalFile(Uri.parse(r.uri), r.name, r.mime.takeIf { it != FileTypes.GENERIC_MIME } ?: mimeOf(r.name), r.size, fromDownload = true)

    /** The existing, still-present download of this remote file, if any. */
    fun downloadedCopy(path: String): DownloadRecord? {
        val server = serverId() ?: return null
        return index.find(server, path).firstOrNull { exists(Uri.parse(it.uri)) }
    }

    // ---- manage ----------------------------------------------------------------------------------------

    /**
     * Deletes the given downloads from the Downloads collection and the index. Only URIs this app created
     * (those in the index) are touched. Returns how many files the system refused to delete (e.g. after a
     * reinstall the app no longer owns them); those records are dropped anyway so the list stays honest.
     */
    suspend fun delete(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        var failed = 0
        index.list().filter { it.id in ids }.forEach { r ->
            val uri = Uri.parse(r.uri)
            val ok = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            if (!ok && exists(uri)) failed++
        }
        index.remove(ids)
        publish()
        failed
    }

    suspend fun deleteAllDownloads(): Int = delete(index.list().map { it.id }.toSet())

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cache.clear()
        publish()
    }

    /** Unpair / forget server: temporary copies of that computer's files go; the user's downloads stay. */
    fun onServerForgotten() {
        cache.clear()
        _cacheBytes.value = 0
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    // ---- hand off to other apps ------------------------------------------------------------------------

    /** `ACTION_VIEW` chooser for [f] (read grant only, content:// only — never a raw file path). */
    fun viewIntent(f: LocalFile): Intent {
        // An unknown type as `*/*`: "application/octet-stream" matches almost no app, so the chooser was empty.
        val type = if (f.mime == FileTypes.GENERIC_MIME) "*/*" else f.mime
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(f.name, f.uri)
        return Intent.createChooser(view, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareIntent(f: LocalFile): Intent {
        val send = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, f.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(f.name, f.uri)
        return Intent.createChooser(send, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun authority() = context.packageName + ".fileprovider"

    /** The app's own table first, then the platform's (it knows rar, epub, odt, ...). */
    private fun mimeOf(name: String): String {
        val own = FileTypes.mimeFor(name)
        if (own != FileTypes.GENERIC_MIME) return own
        val ext = FileTypes.extension(name)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: own
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val DOWNLOAD_FOLDER = "SwitchTether"
        const val UNKNOWN_META_MAX_AGE_MS = 10L * 60 * 1000
        private const val STAT_TIMEOUT_MS = 5_000L

        /** [path]'s size and modified time in its folder's listing; null when it is not listed (or is a folder). */
        fun metaIn(listing: io.github.tianli0.switchtether.remote.protocol.FsListing, path: String): RemoteMeta? {
            val entry = listing.entries.firstOrNull { !it.isDir && (FileTypes.samePath(it.path, path) || (it.path.isEmpty() && it.name == FileTypes.fileName(path))) }
                ?: return null
            return RemoteMeta(entry.size.takeIf { it > 0 }, entry.modifiedMs?.takeIf { it > 0 })
        }
    }
}
