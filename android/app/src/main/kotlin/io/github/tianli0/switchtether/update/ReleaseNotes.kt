package io.github.tianli0.switchtether.update

/**
 * What the update sheet shows of a GitHub release body: only the part in the app's language (the
 * releases carry English first, then a "中文说明" section), without the release's own heading (the
 * sheet title already names the version) and without the download list and checksum notes, which
 * are for people downloading by hand; the updater picks and checks the APK itself.
 */
object ReleaseNotes {
    private val ZH_SECTION = Regex("""^\s*#{1,6}\s*中文说明\s*$""")
    private val HEADING = Regex("""^\s*(#{1,6}\s+.+|\*\*[^*]+\*\*\s*)$""")
    private val DOWNLOADS = Regex("""^\s*(#{1,6}\s*|\*\*)\s*(Downloads?|下载)\s*(\*\*)?\s*[:：]?\s*$""", RegexOption.IGNORE_CASE)
    private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
    private val RELEASE_TITLE = Regex("""^\s*#{1,2}\s+SwitchTether\b.*$""", RegexOption.IGNORE_CASE)

    fun forDisplay(body: String, chinese: Boolean): String {
        val lines = body.replace("\r\n", "\n").lines()
        val zhAt = lines.indexOfFirst { ZH_SECTION.matches(it) }
        val part = when {
            zhAt < 0 -> lines
            chinese -> lines.drop(zhAt + 1)
            else -> lines.take(zhAt)
        }
        val out = ArrayList<String>()
        var skipping = false
        for (line in part) {
            if (DOWNLOADS.matches(line)) { skipping = true; continue }
            if (skipping) {
                if (HEADING.matches(line) || RULE.matches(line)) skipping = false else continue
            }
            if (RELEASE_TITLE.matches(line) || RULE.matches(line)) continue
            out += line
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
