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

/** Icon family of a list entry (mapped to Phosphor icons in the UI). */
enum class FileIcon { FOLDER, CODE, TEXT, MARKDOWN, IMAGE, PDF, VIDEO, AUDIO, ARCHIVE, DOCUMENT, SHEET, SLIDES, LOCKED, OTHER }

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
        "markdown" to "text/markdown", "mdx" to "text/markdown", "rst" to "text/plain", "tsv" to "text/tab-separated-values",
        "jsonc" to "application/json", "json5" to "application/json", "mjs" to "text/javascript", "cjs" to "text/javascript",
        "mts" to "text/plain", "cts" to "text/plain", "hpp" to "text/plain", "cc" to "text/plain", "cxx" to "text/plain",
        "hh" to "text/plain", "m" to "text/plain", "mm" to "text/plain", "scala" to "text/plain", "groovy" to "text/plain",
        "php" to "text/plain", "pl" to "text/plain", "pm" to "text/plain", "lua" to "text/plain", "r" to "text/plain",
        "zsh" to "text/plain", "bash" to "text/plain", "fish" to "text/plain", "psm1" to "text/plain", "cmd" to "text/plain",
        "scss" to "text/css", "less" to "text/css", "cfg" to "text/plain", "conf" to "text/plain", "env" to "text/plain",
        "lock" to "text/plain", "proto" to "text/plain", "graphql" to "text/plain", "tf" to "text/plain", "nix" to "text/plain",
        "zig" to "text/plain", "ex" to "text/plain", "exs" to "text/plain", "hs" to "text/plain", "ml" to "text/plain",
        "diff" to "text/x-diff", "patch" to "text/x-diff", "coffee" to "text/plain", "editorconfig" to "text/plain",
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

    /** Whether the file is Markdown (shown rendered, with a toggle to the source). */
    fun isMarkdown(name: String): Boolean = extension(name) in setOf("md", "markdown", "mdx")

    private val codeExts = setOf(
        "kt", "kts", "java", "rs", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "c", "h", "cpp", "hpp", "cc", "cxx",
        "hh", "m", "mm", "go", "swift", "rb", "cs", "dart", "vue", "svelte", "scala", "groovy", "gradle", "php", "pl", "pm", "lua",
        "r", "sh", "bash", "zsh", "fish", "ps1", "psm1", "bat", "cmd", "sql", "html", "htm", "css", "scss", "less", "json", "jsonc",
        "json5", "xml", "yml", "yaml", "toml", "proto", "graphql", "tf", "nix", "zig", "ex", "exs", "hs", "ml", "coffee", "svg",
    )
    private val archiveExts = setOf("zip", "gz", "tgz", "tar", "7z", "rar", "xz", "bz2", "zst", "jar", "apk", "aab", "whl")
    private val docExts = setOf("doc", "docx", "odt", "rtf", "pages")
    private val sheetExts = setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers")
    private val slideExts = setOf("ppt", "pptx", "odp", "key")
    private val lockedNames = setOf(".env", "id_rsa", "id_ed25519", "id_ecdsa", ".npmrc", ".pypirc", ".netrc")

    /** Which icon the Files list shows for an entry (by kind and extension; sensitive files get a lock). */
    fun iconOf(name: String, isDir: Boolean, sensitive: Boolean = false): FileIcon {
        if (isDir) return FileIcon.FOLDER
        val n = fileName(name).lowercase()
        if (sensitive || n in lockedNames || n.startsWith(".env.")) return FileIcon.LOCKED
        if (isMarkdown(n)) return FileIcon.MARKDOWN
        val ext = extension(n)
        return when {
            ext == "pdf" -> FileIcon.PDF
            ext in images -> FileIcon.IMAGE
            ext in videos -> FileIcon.VIDEO
            ext in audios -> FileIcon.AUDIO
            ext in archiveExts -> FileIcon.ARCHIVE
            ext in sheetExts -> FileIcon.SHEET
            ext in docExts -> FileIcon.DOCUMENT
            ext in slideExts -> FileIcon.SLIDES
            ext in codeExts -> FileIcon.CODE
            ext in texts || ext.isEmpty() -> FileIcon.TEXT
            else -> FileIcon.OTHER
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
