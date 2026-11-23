package io.github.tianli0.switchtether.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

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
    data object PortForward : Route
    data object PickFolder : Route
    data object Providers : Route
    data object Usage : Route
    data object Mcp : Route
    data object Skills : Route
    data object Prompts : Route
    data object NotificationPrefs : Route
    data object Licenses : Route
    data object DesktopInfo : Route
}

/** One screen in the navigation: a tab root ([route] null) or a detail route [depth] deep in that tab. */
data class NavEntry(val tab: Tab, val route: Route?, val depth: Int) {
    /** Stable per back-stack position: keys saved UI state (scroll, fields) of that screen. */
    val key: String get() = "${tab.name}/$depth/$route"
}

/**
 * Typed navigation: the selected tab plus one back stack PER TAB, so switching tabs keeps each
 * tab's place (Sessions → a session → Home → Sessions returns to that session). Back pops the
 * current tab's stack, then returns to Home, then leaves the app (system "back to home"
 * animation). Held in a ViewModel so it survives configuration changes.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
        private set
    private val stacks: Map<Tab, androidx.compose.runtime.snapshots.SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf() }

    /** The current tab's back stack. */
    val stack: List<Route> get() = stacks.getValue(tab)

    val top: Route? get() = stack.lastOrNull()

    val current: NavEntry get() = NavEntry(tab, top, stack.size)

    /** Where back goes from here, or null when back leaves the app. */
    val previous: NavEntry?
        get() = when {
            stack.isNotEmpty() -> NavEntry(tab, stack.getOrNull(stack.size - 2), stack.size - 1)
            tab != Tab.HOME -> stacks.getValue(Tab.HOME).let { NavEntry(Tab.HOME, it.lastOrNull(), it.size) }
            else -> null
        }

    /** Selecting the current tab again returns to its root. */
    fun select(t: Tab) {
        if (t == tab) stacks.getValue(t).clear() else tab = t
    }

    fun push(r: Route) {
        val s = stacks.getValue(tab)
        if (s.lastOrNull() != r) s.add(r)
    }

    /** Replaces the current page (e.g. a session that was resumed opens in its place). */
    fun replace(r: Route) {
        val s = stacks.getValue(tab)
        if (s.isEmpty()) s.add(r) else s[s.lastIndex] = r
    }

    /** @return false when there is nothing to pop (let the system handle back). */
    fun back(): Boolean {
        val s = stacks.getValue(tab)
        return when {
            s.isNotEmpty() -> {
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

    fun reset() {
        stacks.values.forEach { it.clear() }
        tab = Tab.HOME
    }
}
