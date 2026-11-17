package io.github.tianli0.switchtether.files

/** How the app presents a remote file. */
enum class FileKind {
    IMAGE, PDF, VIDEO, AUDIO,

    /** Shown by the text/code viewer (reads through `fs/read`, in memory). */
    TEXT,

    /** Anything else: offered to other apps ("Open with…"). */
    OTHER;

    /** Previewed from a local copy (cache or download) rather than from `fs/read` text. */
    val isMedia: Boolean get() = this == IMAGE || this == PDF || this == VIDEO || this == AUDIO
}

/** Pure file-name helpers: type detection by extension, safe local names, remote-path parsing. */
object FileTypes {
    private val images = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "ico" to "image/x-icon",
    )
    private val videos = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
        "mkv" to "video/x-matroska", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
    )
    private val audios = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac",
    )
    private val texts = mapOf(
        "txt" to "text/plain", "log" to "text/plain", "md" to "text/markdown", "json" to "application/json",
        "csv" to "text/csv", "xml" to "text/xml", "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "yml" to "text/plain", "yaml" to "text/plain", "toml" to "text/plain", "ini" to "text/plain",
        "kt" to "text/plain", "kts" to "text/plain", "java" to "text/plain", "rs" to "text/plain", "py" to "text/plain",
        "js" to "text/javascript", "jsx" to "text/plain", "ts" to "text/plain", "tsx" to "text/plain", "sh" to "text/plain",
        "c" to "text/plain", "h" to "text/plain", "cpp" to "text/plain", "go" to "text/plain", "sql" to "text/plain",
        "gradle" to "text/plain", "properties" to "text/plain", "swift" to "text/plain", "rb" to "text/plain",
        "cs" to "text/plain", "ps1" to "text/plain", "bat" to "text/plain", "dart" to "text/plain",
        "vue" to "text/plain", "svelte" to "text/plain", "svg" to "image/svg+xml",
    )
    private val others = mapOf(
        "zip" to "application/zip", "gz" to "application/gzip", "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed", "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    private val windowsDrive = Regex("^[A-Za-z]:[\\\\/]")
    private const val BAD_NAME_CHARS = "<>:\"|?*"

    /** Lower-case extension without the dot; "" when none (a leading dot such as `.env` is not an extension). */
    fun extension(name: String): String {
        val n = fileName(name)
        val i = n.lastIndexOf('.')
        return if (i <= 0 || i == n.lastIndex) "" else n.substring(i + 1).lowercase()
    }

    fun mimeFor(name: String): String {
        val ext = extension(name)
        if (ext == "pdf") return "application/pdf"
        return images[ext] ?: videos[ext] ?: audios[ext] ?: texts[ext] ?: others[ext] ?: "application/octet-stream"
    }

    fun kindOf(name: String): FileKind {
        val ext = extension(name)
        return when {
            ext == "pdf" -> FileKind.PDF
            ext in images -> FileKind.IMAGE
            ext in videos -> FileKind.VIDEO
            ext in audios -> FileKind.AUDIO
            ext in texts -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    /** Last segment of a Windows or POSIX path. */
    fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /** A name safe to use as a local file name: no separators/control characters, bounded length, never blank. */
    fun safeFileName(name: String, fallback: String = "file"): String {
        val cleaned = fileName(name).map { if (it.isISOControl() || it in BAD_NAME_CHARS) '_' else it }.joinToString("").trim().trim('.')
        if (cleaned.isEmpty()) return fallback
        if (cleaned.length <= 120) return cleaned
        val ext = extension(cleaned).takeIf { it.isNotEmpty() && it.length <= 12 }
        return if (ext == null) cleaned.take(120) else cleaned.take(120 - ext.length - 1) + "." + ext
    }

    /** Whether a markdown link target in chat points at a file on the desktop (not a web or app URL). */
    fun isLocalPath(link: String): Boolean {
        val s = link.trim()
        if (s.isEmpty()) return false
        if (s.startsWith("file://")) return true
        if (s.contains("://") || s.startsWith("//") || s.startsWith("#")) return false
        return s.startsWith("/") || s.startsWith("\\\\") || windowsDrive.containsMatchIn(s)
    }

    /** Resolve chat links against the session's desktop cwd; strip source-line anchors. */
    fun resolveChatPath(link: String, cwd: String?): String? {
        if (link.startsWith("#") || link.startsWith("//")) return null
        var path = stripFileScheme(link)
        if (!isLocalPath(link)) {
            if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(link) || cwd.isNullOrBlank()) return null
            path = cwd.trimEnd('/', '\\') + "/" + path
        }
        path = path.replace(Regex("#L[0-9]+(?:C[0-9]+)?(?:-L?[0-9]+(?:C[0-9]+)?)?$"), "")
            .replace(Regex(":[0-9]+(?::[0-9]+)?$"), "")
        return path.takeIf { it.isNotBlank() }
    }

    /** `file:///C:/x/y.pdf` → `C:/x/y.pdf`; plain paths pass through. */
    fun stripFileScheme(link: String): String {
        val s = link.trim()
        if (!s.startsWith("file://")) return s
        val rest = runCatching { java.net.URLDecoder.decode(s.removePrefix("file://").replace("+", "%2B"), "UTF-8") }.getOrDefault(s.removePrefix("file://"))
        return if (Regex("^/[A-Za-z]:[\\\\/]").containsMatchIn(rest)) rest.substring(1) else rest
    }
}
