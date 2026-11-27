package io.github.tianli0.switchtether.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.tianli0.switchtether.ui.sessions.MAX_ATTACHMENT_BYTES
import java.io.File
import java.io.InputStream

/** Reads a SEND / SEND_MULTIPLE intent into a [RawShare] (the parsing itself is [ShareIntake]). */
object ShareIntents {
    fun read(intent: Intent): RawShare {
        val texts = buildList {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { add(it.toString()) }
            runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()?.forEach { add(it.toString()) }
        }
        val streams = buildList {
            runCatching { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()?.let { add(it.toString()) }
            runCatching { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()?.forEach { add(it.toString()) }
        }
        val clipUris = ArrayList<String>()
        val clipTexts = ArrayList<String>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                item.uri?.let { clipUris += it.toString() }
                item.text?.let { clipTexts += it.toString() }
            }
        }
        return RawShare(
            action = intent.action,
            type = intent.type,
            texts = texts,
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            streams = streams,
            clipUris = clipUris,
            clipTexts = clipTexts,
            shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID),
        )
    }

    /** True for an authority served by this app (FileProvider and any other provider of ours). */
    fun ownAuthority(context: Context): (String) -> Boolean = { authority ->
        authority.startsWith(context.packageName) ||
            runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName == context.packageName }.getOrDefault(false)
    }
}

/**
 * Reading shared files, and keeping them readable while a share waits.
 *
 * A share's read grant belongs to the activity that received it: while SwitchTether's activity
 * lives, the sender's URI is streamed straight into the upload (never held in memory). When the
 * share has to wait (pairing, computer offline) the grant could end first, so it is made
 * persistable when the sender allows that, and otherwise the file is copied into
 * `cache/shared/` (streamed; skipped over 1 GiB or when the cache volume lacks room).
 */
object ShareFiles {
    private const val DIR = "shared"
    private const val PREFS = "share_grants"
    private const val KEY_PERSISTED = "persisted"
    private const val KEEP_MS = 24L * 60 * 60 * 1000
    private const val FREE_MARGIN = 256L * 1024 * 1024

    /** Display name, size (-1 unknown) and type of a shared file. */
    fun info(context: Context, item: SharedItem): Triple<String, Long, String?> {
        item.cachedPath?.let { path ->
            val f = File(path)
            return Triple(item.cachedName ?: f.name, f.length(), item.mimeType ?: context.contentResolver.getType(Uri.parse(item.uri)))
        }
        val uri = Uri.parse(item.uri)
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: item.mimeType
        val fallback = "file" + (mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: "")
        return Triple(name?.takeIf { it.isNotBlank() }?.let(::safeName) ?: (uri.lastPathSegment?.let(::safeName) ?: fallback), size, mime)
    }

    fun open(context: Context, item: SharedItem): InputStream? =
        item.cachedPath?.let { File(it).inputStream() } ?: context.contentResolver.openInputStream(Uri.parse(item.uri))

    /** Makes [item] readable after the activity's grant ends: a persistable grant, else a cache copy. */
    fun secure(context: Context, item: SharedItem): SharedItem {
        val uri = Uri.parse(item.uri)
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (persisted) {
            rememberPersisted(context, item.uri)
            return item.copy(persisted = true)
        }
        val (name, size, _) = info(context, item)
        if (size > MAX_ATTACHMENT_BYTES) return item // refused as "over 1 GB" anyway
        val root = File(context.cacheDir, DIR)
        if (size > 0 && root.apply { mkdirs() }.usableSpace < size + FREE_MARGIN) return item
        val dir = File(root, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(dir, name)
        return try {
            (context.contentResolver.openInputStream(uri) ?: return item).use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ATTACHMENT_BYTES) throw java.io.IOException("over 1 GiB")
                        out.write(buf, 0, n)
                    }
                }
            }
            item.copy(cachedPath = target.absolutePath, cachedName = name)
        } catch (e: Exception) {
            dir.deleteRecursively()
            item
        }
    }

    /**
     * Housekeeping when a new share arrives or one is cancelled: cache copies older than a day go,
     * and the persistable grants taken for earlier shares are released (except [keep]).
     */
    fun cleanup(context: Context, keep: Collection<SharedItem> = emptyList()) {
        val now = System.currentTimeMillis()
        val keepPaths = keep.mapNotNull { it.cachedPath?.let { p -> File(p).parentFile?.absolutePath } }.toSet()
        File(context.cacheDir, DIR).listFiles()?.forEach { d ->
            if (d.absolutePath !in keepPaths && now - d.lastModified() > KEEP_MS) d.deleteRecursively()
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keepUris = keep.filter { it.persisted }.map { it.uri }.toSet()
        val held = prefs.getStringSet(KEY_PERSISTED, emptySet()).orEmpty()
        held.filter { it !in keepUris }.forEach { u ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().putStringSet(KEY_PERSISTED, held.filter { it in keepUris }.toSet()).apply()
    }

    /** Drops the cache copies of a cancelled share at once. */
    fun discard(context: Context, content: SharedContent) {
        content.items.forEach { it.cachedPath?.let { p -> File(p).parentFile?.deleteRecursively() } }
        cleanup(context)
    }

    private fun rememberPersisted(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_PERSISTED, prefs.getStringSet(KEY_PERSISTED, emptySet()).orEmpty() + uri).apply()
    }

    /** A display name usable as a file name (no path separators, never empty). */
    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().ifEmpty { "file" }.take(200)
}
