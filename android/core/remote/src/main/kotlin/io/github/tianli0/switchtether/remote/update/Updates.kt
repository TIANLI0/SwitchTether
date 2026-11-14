package io.github.tianli0.switchtether.remote.update

import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.arr
import io.github.tianli0.switchtether.remote.protocol.bool
import io.github.tianli0.switchtether.remote.protocol.long
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Semantic version (`MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]`, optional leading `v`), compared
 * by SemVer 2.0 precedence: a pre-release sorts before its release; build metadata is ignored.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVer> {
    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1 // 1.0.0 > 1.0.0-beta
        if (other.pre.isEmpty()) return -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort before alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + (if (pre.isEmpty()) "" else "-" + pre.joinToString("."))

    companion object {
        private val RE = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String?): SemVer? {
            val m = RE.matchEntire(text?.trim() ?: return null) ?: return null
            return SemVer(
                m.groupValues[1].toInt(),
                m.groupValues[2].ifEmpty { "0" }.toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
                m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
            )
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** One GitHub release (`GET /repos/{owner}/{repo}/releases/latest` or an item of `/releases`). */
data class ReleaseInfo(
    val tag: String,
    val version: SemVer,
    val name: String,
    val notes: String,
    val prerelease: Boolean,
    val htmlUrl: String?,
    val publishedAt: String?,
    val assets: List<ReleaseAsset>,
) {
    /** `SwitchTether-<version>-android.apk` (asset naming contract with the release workflow). */
    val apk: ReleaseAsset? get() = assets.firstOrNull { it.name == apkName(version) } ?: assets.firstOrNull { it.name == "SwitchTether-${tag.removePrefix("v")}-android.apk" }
    val checksums: ReleaseAsset? get() = assets.firstOrNull { it.name == "SHA256SUMS.txt" }

    companion object {
        fun apkName(v: SemVer) = "SwitchTether-$v-android.apk"

        fun parse(o: JsonObject): ReleaseInfo? {
            if (o.bool("draft") == true) return null
            val tag = o.str("tag_name") ?: return null
            val version = SemVer.parse(tag) ?: return null
            return ReleaseInfo(
                tag = tag,
                version = version,
                name = o.str("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = o.str("body").orEmpty(),
                prerelease = o.bool("prerelease") == true || version.isPrerelease,
                htmlUrl = o.str("html_url"),
                publishedAt = o.str("published_at"),
                assets = o["assets"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { a ->
                    val n = a.str("name") ?: return@mapNotNull null
                    val u = a.str("browser_download_url") ?: return@mapNotNull null
                    ReleaseAsset(n, u, a.long("size") ?: -1)
                },
            )
        }

        /** Parses `/releases/latest` (an object) or `/releases` (an array). */
        fun parseAll(json: String): List<ReleaseInfo> = when (val e = ProtocolJson.parseToJsonElement(json)) {
            is JsonObject -> listOfNotNull(parse(e))
            is JsonArray -> e.mapNotNull { it.obj()?.let(::parse) }
            else -> emptyList()
        }
    }
}

object Sha256Sums {
    /** `SHA256SUMS.txt` lines `<64 hex>  <file>` (sha256sum format; `*` binary marker allowed). */
    fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").matchEntire(line) ?: return@mapNotNull null
            m.groupValues[2].trim() to m.groupValues[1].lowercase()
        }
        .toMap()

    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(file: File): String = file.inputStream().use(::of)
}

/** Result of one check. */
sealed interface UpdateCheck {
    /** A newer release is available. */
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data class UpToDate(val latest: ReleaseInfo?) : UpdateCheck
    /** HTTP 304: nothing changed since the stored ETag (the caller re-evaluates its cached JSON). */
    data object NotModified : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/** Picks the newest acceptable release that is newer than [current]. */
fun chooseUpdate(releases: List<ReleaseInfo>, current: SemVer, includePrerelease: Boolean): ReleaseInfo? =
    releases.filter { includePrerelease || !it.prerelease }.maxByOrNull { it.version }?.takeIf { it.version > current }

/**
 * Queries the public GitHub releases API without authentication. Uses `If-None-Match` with the
 * ETag of the previous response (304s do not count against the unauthenticated rate limit).
 */
class GithubReleases(
    private val client: OkHttpClient,
    private val repo: String = "TIANLI0/SwitchTether",
    private val baseUrl: String = "https://api.github.com",
    private val userAgent: String = "SwitchTether-Android",
) {
    data class Response(val code: Int, val etag: String?, val body: String?)

    fun fetch(includePrerelease: Boolean, etag: String?): Response {
        val url = if (includePrerelease) "$baseUrl/repos/$repo/releases?per_page=10" else "$baseUrl/repos/$repo/releases/latest"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        client.newCall(req).execute().use { r ->
            return Response(r.code, r.header("ETag"), if (r.code == 200) r.body.string() else null)
        }
    }
}
