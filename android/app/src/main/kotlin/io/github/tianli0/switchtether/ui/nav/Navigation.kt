package io.github.tianli0.switchtether.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Tab { HOME, SESSIONS, WORKSPACE, MANAGE }

/**
 * Detail destinations. On a phone each one is its own activity ([io.github.tianli0.switchtether.PageActivity]),
 * so the system draws opening and predictive back between pages; on a tablet the one chosen from
 * the list shows beside it. Routes travel in the activity's intent, so they must stay serializable.
 */
@Serializable
sealed interface Route {
    @Serializable data class SessionDetail(val sessionId: String, val title: String?) : Route
    /** A Claude Code sub-agent's own steps; [agentId] inside [sessionId]. */
    @Serializable data class Subagent(val sessionId: String, val agentId: String, val title: String?, val agent: String = "claude_code") : Route
    /** All sub-agents of a session as a branch tree. */
    @Serializable data class SubagentTree(val sessionId: String, val agent: String = "claude_code") : Route
    /** Claude Code session tools (permission mode, context, plan, MCP, side question, rename, export). */
    @Serializable data class SessionTools(val sessionId: String) : Route
    @Serializable data object NewSession : Route
    @Serializable data class Terminal(val id: String?) : Route
    @Serializable data object Files : Route
    @Serializable data object Downloads : Route
    @Serializable data object Git : Route
    /** "本轮改动": the Git review of a session's folder, [turnFiles] = what its latest turn edited. */
    @Serializable data class GitReview(val cwd: String, val turnFiles: List<String>) : Route
    @Serializable data object PortForward : Route
    /** DeepSeek Harness Web in a WebView over a port forward (docs/ANDROID_UI.md §31). */
    @Serializable data object DshWeb : Route
    @Serializable data object PickFolder : Route
    /** Manage (providers, usage, MCP, skills, prompts, settings): a page opened from Home, not a tab. */
    @Serializable data object Manage : Route
    @Serializable data object Providers : Route
    @Serializable data object Usage : Route
    /** One project's usage and its sessions (by project name, as the session list shows it). */
    @Serializable data class ProjectUsage(val project: String) : Route
    @Serializable data object Mcp : Route
    @Serializable data object Skills : Route
    @Serializable data object Prompts : Route
    @Serializable data object NotificationPrefs : Route
    @Serializable data object Licenses : Route
    @Serializable data object About : Route
    /** About → 诊断记录: process-exit records (docs/ANDROID_UI.md §37). */
    @Serializable data object Diagnostics : Route
    @Serializable data object DesktopInfo : Route
    /** Settings → 使用统计: what the paired phones did (PROTOCOL.md §7.15). */
    @Serializable data object RemoteStats : Route

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(route: Route): String = json.encodeToString(serializer(), route)

        /** Null for a malformed or outdated value (a restored activity from an older build). */
        fun decode(value: String?): Route? = value?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** Opens, replaces and leaves pages; one per window (main shell, a page activity, a tablet's detail pane). */
interface Navigator {
    fun open(route: Route)

    /** Puts [route] in the current page's place (e.g. a session that was resumed). */
    fun replace(route: Route)

    /** Leaves the current page. */
    fun back()
}

/**
 * The main window's state: the selected tab and, on a tablet, the route chosen from each tab's list
 * for the pane beside it. Back clears that choice, then returns to Home, then leaves the app (system
 * "back to home" animation). Pages themselves are activities and keep no state here.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
        private set
    private val selections = mutableStateMapOf<Tab, Route>()

    /** What the current tab's detail pane shows (tablets only; always null on a phone). */
    val selection: Route? get() = selections[tab]

    /** Selecting the current tab again returns to its root. */
    fun select(t: Tab) {
        if (t == tab) selections.remove(t) else tab = t
    }

    /** Opens [r] from the tab's list in the detail pane beside it, in place of what it showed. */
    fun openFromList(r: Route) {
        selections[tab] = r
    }

    /**
     * The window became too narrow for two panes: the current tab's choice is handed back to open as
     * its own page, the other tabs' choices are dropped.
     */
    fun takeSelection(): Route? {
        val r = selections[tab]
        selections.clear()
        return r
    }

    /** @return false when there is nothing to go back to (let the system handle back). */
    fun back(): Boolean = when {
        selections.remove(tab) != null -> true
        tab != Tab.HOME -> {
            tab = Tab.HOME
            true
        }
        else -> false
    }

    /** Set by the bar's search key; the Sessions page opens its search field and clears it. */
    var sessionsSearchRequested by mutableStateOf(false)

    /** The bar's search key: the Sessions list (not a page opened from it) with its search field open. */
    fun openSessionsSearch() {
        selections.remove(Tab.SESSIONS)
        tab = Tab.SESSIONS
        sessionsSearchRequested = true
    }
}
