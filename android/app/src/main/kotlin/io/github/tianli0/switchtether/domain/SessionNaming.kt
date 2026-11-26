package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.remote.protocol.SessionSummary

/**
 * How a session is named in the UI.
 *
 *  - **Title** = what the user is doing: the agent's own conversation title (`/rename`, else the
 *    generated one). Never replaced by the project name; a session without a title is shown as
 *    "unnamed" so the project (shown separately) is not mistaken for the task.
 *  - **Project** = which codebase: the working directory's folder, except agent worktrees
 *    (`<project>/.claude/worktrees/<name>`), which belong to the project that contains them.
 *
 * Sub-agent work is part of its parent session (events nested under the tool call) and never a
 * session of its own, so it has neither a title nor a project here.
 */
object SessionNaming {
    /** Registry slugs such as "myapp-3f" that older desktops sent as titles. */
    private val SLUG = Regex("^[a-z0-9][a-z0-9._]*-[0-9a-f]{1,4}$")

    fun title(s: SessionSummary): String? {
        val raw = s.title?.let { io.github.tianli0.switchtether.remote.session.InjectedText.clean(it).text }
        val t = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("<task-notification>") } ?: return null
        val project = projectName(s)
        // Older desktop builds sent the auto-generated "<project>-<hex>" registry name as the title.
        if (project != null && SLUG.matches(t) && t.startsWith(project.lowercase() + "-")) return null
        return t
    }

    fun projectName(s: SessionSummary): String? =
        s.cwd?.let(::fromCwd) ?: s.project?.trim()?.takeIf { it.isNotEmpty() }

    /** Mirrors `SessionSummary::project_from_cwd` in the Remote Core. */
    fun fromCwd(cwd: String): String? {
        val parts = cwd.split('/', '\\').filter { it.isNotEmpty() }
        val wt = (0 until parts.size - 1).firstOrNull {
            parts[it] in setOf(".claude", ".codex", ".cursor") && parts[it + 1] == "worktrees"
        }
        val name = when {
            wt != null && wt > 0 -> parts[wt - 1]
            else -> parts.indexOf(".worktrees").takeIf { it > 0 }?.let { parts[it - 1] } ?: parts.lastOrNull()
        }
        return name?.trimEnd(':')?.takeIf { it.isNotEmpty() }
    }

    /**
     * A title for a list row: at most about two lines on a phone. Wide (CJK, emoji) characters count
     * twice; longer titles are cut at [maxUnits] with "…". The full title stays on the session page.
     */
    fun rowTitle(title: String, maxUnits: Int = 64): String {
        var units = 0
        var i = 0
        while (i < title.length) {
            val cp = title.codePointAt(i)
            units += if (cp >= 0x1100) 2 else 1
            if (units > maxUnits) return title.substring(0, i).trimEnd() + "…"
            i += Character.charCount(cp)
        }
        return title
    }
}
