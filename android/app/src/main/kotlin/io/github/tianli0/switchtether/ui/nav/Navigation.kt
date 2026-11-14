package io.github.tianli0.switchtether.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class Tab { HOME, SESSIONS, WORKSPACE, MANAGE, SETTINGS }

/** Detail destinations pushed above the tabs. */
sealed interface Route {
    data class SessionDetail(val sessionId: String, val title: String?) : Route
    data object NewSession : Route
    data object Providers : Route
    data object Usage : Route
    data object Mcp : Route
    data object Skills : Route
    data object Prompts : Route
    data object NotificationPrefs : Route
    data object Licenses : Route
    data object DesktopInfo : Route
}

/**
 * Minimal typed navigation: one selected tab + a stack of detail routes. Held in a ViewModel so it
 * survives configuration changes (rotation, window resize). No navigation library needed.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
        private set
    val stack = mutableStateListOf<Route>()

    val top: Route? get() = stack.lastOrNull()

    fun select(t: Tab) {
        if (t == tab) stack.clear()
        tab = t
        stack.clear()
    }

    fun push(r: Route) {
        if (stack.lastOrNull() != r) stack.add(r)
    }

    /** @return false when there is nothing to pop (let the system handle back). */
    fun back(): Boolean = when {
        stack.isNotEmpty() -> {
            stack.removeAt(stack.lastIndex)
            true
        }
        tab != Tab.HOME -> {
            tab = Tab.HOME
            true
        }
        else -> false
    }

    fun reset() {
        stack.clear()
        tab = Tab.HOME
    }
}
