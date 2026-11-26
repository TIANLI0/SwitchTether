package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.Page
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import io.github.tianli0.switchtether.ui.nav.colorOsSlide
import io.github.suqi8.coui.kmp.nav.transition.NavSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-tab back stacks coui-nav's NavDisplay renders (root page first). */
class NavStackTest {
    @Test
    fun everyTabStackStartsWithItsRootAndStackHidesIt() {
        val nav = NavViewModel()
        Tab.entries.forEach { t ->
            assertEquals(listOf(Page(t, null, 0)), nav.backStack(t).toList())
        }
        nav.push(Route.Files)
        assertEquals(listOf<Route>(Route.Files), nav.stack)
        assertEquals(2, nav.backStack(Tab.HOME).size)
        assertEquals(Route.Files, nav.top)
    }

    @Test
    fun theSameRouteTwiceIsTwoEntries() {
        // NavDisplay rejects equal keys on one stack; a route reached again further up must not crash.
        val nav = NavViewModel()
        nav.push(Route.SessionDetail("a", null))
        nav.push(Route.SessionTools("a"))
        nav.push(Route.SessionDetail("a", null))
        val keys = nav.backStack(Tab.HOME).toList()
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.size, keys.map { it.toString() }.toSet().size)
        // An immediate repeat (double tap) is still ignored.
        nav.push(Route.SessionDetail("a", null))
        assertEquals(4, keys.size)
        assertEquals(4, nav.backStack(Tab.HOME).size)
    }

    @Test
    fun replaceGivesTheNewPageItsOwnIdentity() {
        val nav = NavViewModel()
        nav.push(Route.SessionDetail("a", null))
        val before = nav.backStack(Tab.HOME).last()
        nav.replace(Route.SessionDetail("b", null))
        assertEquals(listOf<Route>(Route.SessionDetail("b", null)), nav.stack)
        assertNotEquals(before, nav.backStack(Tab.HOME).last())
    }

    @Test
    fun backPopsThenReturnsHomeThenLetsTheSystemHandleIt() {
        val nav = NavViewModel()
        nav.select(Tab.WORKSPACE)
        nav.push(Route.Git)
        assertTrue(nav.back())
        assertTrue(nav.stack.isEmpty())
        assertEquals(Tab.WORKSPACE, nav.tab)
        assertTrue(nav.back())
        assertEquals(Tab.HOME, nav.tab)
        assertFalse(nav.back())
    }

    @Test
    fun popKeepsTheRootAndOnlyTouchesItsTab() {
        val nav = NavViewModel()
        nav.push(Route.About)
        nav.select(Tab.MANAGE)
        nav.push(Route.Usage)
        nav.pop(Tab.HOME)
        nav.pop(Tab.HOME)
        assertEquals(1, nav.backStack(Tab.HOME).size)
        assertEquals(listOf<Route>(Route.Usage), nav.stack)
    }

    @Test
    fun selectingTheCurrentTabAgainReturnsToItsRoot() {
        val nav = NavViewModel()
        nav.push(Route.Files)
        nav.push(Route.Downloads)
        nav.select(Tab.HOME)
        assertEquals(listOf(Page(Tab.HOME, null, 0)), nav.backStack(Tab.HOME).toList())
    }

    @Test
    fun theBarSearchKeyOpensTheSessionsListWithItsSearch() {
        val nav = NavViewModel()
        nav.select(Tab.SESSIONS)
        nav.push(Route.SessionDetail("a", null))
        nav.select(Tab.HOME)
        nav.openSessionsSearch()
        assertEquals(Tab.SESSIONS, nav.tab)
        // The list itself, not the session that was open on that tab.
        assertTrue(nav.stack.isEmpty())
        assertTrue(nav.sessionsSearchRequested)
    }

    @Test
    fun theSwipeFollowsTheReadingDirectionAndTurnsOffWithPredictiveBack() {
        assertEquals(NavSwipeDirection.LeftToRight, colorOsSlide(swipe = true, rtl = false).dismissDirection)
        assertEquals(NavSwipeDirection.RightToLeft, colorOsSlide(swipe = true, rtl = true).dismissDirection)
        assertEquals(NavSwipeDirection.None, colorOsSlide(swipe = false, rtl = false).dismissDirection)
    }
}
