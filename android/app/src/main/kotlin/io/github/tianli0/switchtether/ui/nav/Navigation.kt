package io.github.tianli0.switchtether.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.suqi8.coui.kmp.nav.core.NavBackStack
import io.github.suqi8.coui.kmp.nav.core.NavKey
import io.github.suqi8.coui.kmp.nav.core.navBackStackOf

enum class Tab { HOME, SESSIONS, WORKSPACE, MANAGE }

/** Detail destinations pushed above the tabs. */
sealed interface Route {
    data class SessionDetail(val sessionId: String, val title: String?) : Route
    /** A Claude Code sub-agent's own steps; [agentId] inside [sessionId]. */
    data class Subagent(val sessionId: String, val agentId: String, val title: String?, val agent: String = "claude_code") : Route
    /** All sub-agents of a session as a branch tree. */
    data class SubagentTree(val sessionId: String, val agent: String = "claude_code") : Route
    /** Claude Code session tools (permission mode, context, plan, MCP, side question, rename, export). */
    data class SessionTools(val sessionId: String) : Route
    data object NewSession : Route
    data class Terminal(val id: String?) : Route
    data object Files : Route
    data object Downloads : Route
    data object Git : Route
    /** "本轮改动": the Git review of a session's folder, [turnFiles] = what its latest turn edited. */
    data class GitReview(val cwd: String, val turnFiles: List<String>) : Route
    data object PortForward : Route
    /** DeepSeek Harness Web in a WebView over a port forward (docs/ANDROID_UI.md §31). */
    data object DshWeb : Route
    data object PickFolder : Route
    /** Manage (providers, usage, MCP, skills, prompts, settings): a page opened from Home, not a tab. */
    data object Manage : Route
    data object Providers : Route
    data object Usage : Route
    /** One project's usage and its sessions (by project name, as the session list shows it). */
    data class ProjectUsage(val project: String) : Route
    data object Mcp : Route
    data object Skills : Route
    data object Prompts : Route
    data object NotificationPrefs : Route
    data object Licenses : Route
    data object About : Route
    /** About → 诊断记录: process-exit records (docs/ANDROID_UI.md §37). */
    data object Diagnostics : Route
    data object DesktopInfo : Route
}

/**
 * One page on a tab's back stack: the tab's root ([route] null) or a detail [route]. [serial] makes
 * every push its own entry, so the same route twice on one stack still has two identities (the
 * entry's saved UI state and its place in the stack motion are keyed by this value).
 */
data class Page(val tab: Tab, val route: Route?, val serial: Int) : NavKey

/**
 * Typed navigation: the selected tab plus one back stack PER TAB, so switching tabs keeps each
 * tab's place (Sessions → a session → Home → Sessions returns to that session). Back pops the
 * current tab's stack, then returns to Home, then leaves the app (system "back to home"
 * animation). Held in a ViewModel so it survives configuration changes.
 *
 * Each tab's stack starts with its root [Page] and is rendered by coui-nav's `NavDisplay`
 * ([AppNavHost]); [stack] is the same stack as routes, without the root.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
        private set
    private val stacks: Map<Tab, NavBackStack> = Tab.entries.associateWith { navBackStackOf(Page(it, null, 0)) }
    private var serial = 0

    /** [t]'s back stack of [Page]s, root first. */
    fun backStack(t: Tab): NavBackStack = stacks.getValue(t)

    /** The current tab's detail routes (its root not included). */
    val stack: List<Route> get() = routes(tab)

    val top: Route? get() = stack.lastOrNull()

    private fun routes(t: Tab): List<Route> = stacks.getValue(t).mapNotNull { (it as Page).route }

    private fun page(r: Route) = Page(tab, r, ++serial)

    /** Selecting the current tab again returns to its root. */
    fun select(t: Tab) {
        if (t == tab) clear(stacks.getValue(t)) else tab = t
    }

    fun push(r: Route) {
        val s = stacks.getValue(tab)
        if ((s.last() as Page).route != r) s.add(page(r))
    }

    /**
     * Opens [r] from the tab's list beside the detail pane (two-pane layout): it takes the place of
     * whatever the detail pane showed, so back clears the selection instead of walking through every
     * row tapped before.
     */
    fun openFromList(r: Route) {
        val s = stacks.getValue(tab)
        if (s.size == 2 && (s[1] as Page).route == r) return
        clear(s)
        s.add(page(r))
    }

    /** Replaces the current page (e.g. a session that was resumed opens in its place). */
    fun replace(r: Route) {
        val s = stacks.getValue(tab)
        if (s.size == 1) s.add(page(r)) else s[s.lastIndex] = page(r)
    }

    /** @return false when there is nothing to pop (let the system handle back). */
    fun back(): Boolean {
        val s = stacks.getValue(tab)
        return when {
            s.size > 1 -> {
                s.removeAt(s.lastIndex)
                true
            }
            tab != Tab.HOME -> {
                tab = Tab.HOME
                true
            }
            else -> false
        }
    }

    /** Set by the bar's search key; the Sessions page opens its search field and clears it. */
    var sessionsSearchRequested by mutableStateOf(false)

    /** The bar's search key: the Sessions list (not a page pushed on it) with its search field open. */
    fun openSessionsSearch() {
        clear(stacks.getValue(Tab.SESSIONS))
        tab = Tab.SESSIONS
        sessionsSearchRequested = true
    }

    /** Pops [t]'s stack (its `NavDisplay` back); the root always stays. */
    fun pop(t: Tab) {
        val s = stacks.getValue(t)
        if (s.size > 1) s.removeAt(s.lastIndex)
    }

    fun reset() {
        stacks.values.forEach(::clear)
        tab = Tab.HOME
    }

    private fun clear(s: NavBackStack) {
        while (s.size > 1) s.removeAt(s.lastIndex)
    }
}
