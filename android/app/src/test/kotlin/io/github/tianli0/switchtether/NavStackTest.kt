package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main window's tabs and tablet detail choices; pages themselves are activities. */
class NavStackTest {
    @Test
    fun everyRouteSurvivesTheActivityIntent() {
        val routes = listOf(
            Route.SessionDetail("a", null),
            Route.SessionDetail("a", "标题"),
            Route.Subagent("s", "x", "t"),
            Route.SubagentTree("s", "codex"),
            Route.SessionTools("s"),
            Route.NewSession,
            Route.Terminal(null),
            Route.Terminal("t1"),
            Route.Files,
            Route.Downloads,
            Route.Git,
            Route.GitReview("/w", listOf("a.kt", "b/c.kt")),
            Route.PortForward,
            Route.DshWeb,
            Route.PickFolder,
            Route.Manage,
            Route.Providers,
            Route.Usage,
            Route.ProjectUsage("p"),
            Route.Mcp,
            Route.Skills,
            Route.Prompts,
            Route.NotificationPrefs,
            Route.Licenses,
            Route.About,
            Route.Diagnostics,
            Route.DesktopInfo,
        )
        routes.forEach { assertEquals(it, Route.decode(Route.encode(it))) }
    }

    @Test
    fun aMalformedRouteIsDropped() {
        assertNull(Route.decode(null))
        assertNull(Route.decode("{"))
        assertNull(Route.decode("""{"type":"gone"}"""))
    }

    @Test
    fun backClearsTheChoiceThenReturnsHomeThenLetsTheSystemHandleIt() {
        val nav = NavViewModel()
        nav.select(Tab.WORKSPACE)
        nav.openFromList(Route.Git)
        assertTrue(nav.back())
        assertNull(nav.selection)
        assertEquals(Tab.WORKSPACE, nav.tab)
        assertTrue(nav.back())
        assertEquals(Tab.HOME, nav.tab)
        assertFalse(nav.back())
    }

    @Test
    fun eachTabKeepsItsOwnChoice() {
        val nav = NavViewModel()
        nav.openFromList(Route.About)
        nav.select(Tab.MANAGE)
        nav.openFromList(Route.Usage)
        nav.select(Tab.HOME)
        assertEquals(Route.About, nav.selection)
        nav.select(Tab.MANAGE)
        assertEquals(Route.Usage, nav.selection)
    }

    @Test
    fun selectingTheCurrentTabAgainReturnsToItsRoot() {
        val nav = NavViewModel()
        nav.openFromList(Route.Files)
        nav.select(Tab.HOME)
        assertNull(nav.selection)
    }

    @Test
    fun narrowingHandsTheCurrentChoiceOverAndDropsTheOthers() {
        val nav = NavViewModel()
        nav.openFromList(Route.About)
        nav.select(Tab.SESSIONS)
        nav.openFromList(Route.SessionDetail("a", null))
        assertEquals(Route.SessionDetail("a", null), nav.takeSelection())
        assertNull(nav.selection)
        nav.select(Tab.HOME)
        assertNull(nav.selection)
    }

    @Test
    fun theBarSearchKeyOpensTheSessionsListWithItsSearch() {
        val nav = NavViewModel()
        nav.select(Tab.SESSIONS)
        nav.openFromList(Route.SessionDetail("a", null))
        nav.select(Tab.HOME)
        nav.openSessionsSearch()
        assertEquals(Tab.SESSIONS, nav.tab)
        // The list itself, not the session that was open on that tab.
        assertNull(nav.selection)
        assertTrue(nav.sessionsSearchRequested)
    }
}
