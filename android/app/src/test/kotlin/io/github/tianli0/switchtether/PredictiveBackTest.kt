package io.github.tianli0.switchtether

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.ui.common.LocalPredictiveBack
import io.github.tianli0.switchtether.ui.common.rememberPredictiveBack
import io.github.tianli0.switchtether.ui.nav.DetailPane
import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import io.github.tianli0.switchtether.ui.nav.TabHost
import io.github.tianli0.switchtether.ui.nav.rememberTabTransition
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back inside a window. Between pages the system draws predictive back (each page is an activity),
 * which needs the window to hold NO enabled back callback while nothing in it has to go back.
 */
@RunWith(AndroidJUnit4::class)
class PredictiveBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val nav = NavViewModel()
    private val predictive = mutableStateOf(true)
    private val viewerOpen = mutableStateOf(false)
    private val dispatcher get() = rule.activity.onBackPressedDispatcher

    @Test
    fun homeRootLeavesBackToTheSystem() {
        showTabs()
        rule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun aPageWithNothingOpenLeavesBackToTheSystem() {
        showPage()
        rule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
        rule.runOnIdle { viewerOpen.value = true }
        rule.runOnIdle { assertTrue(dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun tabRootPreviewsHomeAndOnlySwitchesOnCommit() {
        nav.select(Tab.WORKSPACE)
        showTabs()
        startSwipe()
        rule.onNodeWithTag("HOME").assertExists()
        rule.runOnIdle {
            assertEquals(Tab.WORKSPACE, nav.tab)
            dispatcher.dispatchOnBackCancelled()
        }
        rule.onNodeWithTag("HOME").assertDoesNotExist()

        startSwipe()
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(Tab.HOME, nav.tab) }
    }

    @Test
    fun inPageBackPreviewsAndClosesTheViewer() {
        viewerOpen.value = true
        showPage()
        val restingLeft = leftOf("page")
        startSwipe()
        assertTrue(leftOf("page") > restingLeft)
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle {
            assertEquals(false, viewerOpen.value)
            assertFalse(dispatcher.hasEnabledCallbacks())
        }
    }

    @Test
    fun withTheSettingOffTheViewerClosesWithoutFollowingTheFinger() {
        predictive.value = false
        viewerOpen.value = true
        showPage()
        val restingLeft = leftOf("page")
        startSwipe()
        assertEquals(restingLeft, leftOf("page"), 0f)
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(false, viewerOpen.value) }
    }

    @Test
    fun aTabletDetailClearsBeforeTheTabReturnsHome() {
        nav.select(Tab.SESSIONS)
        nav.openFromList(Route.About)
        rule.setContent {
            SwitchTetherTheme {
                val tabs = rememberTabTransition(nav)
                DetailPane(nav, empty = { Box(Modifier.size(96.dp).testTag("empty")) }) { _, r -> Box(Modifier.size(96.dp).testTag(r.toString())) }
                TabHost(nav, tabs) { }
            }
        }
        rule.onNodeWithTag("About").assertExists()
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle {
            assertEquals(null, nav.selection)
            assertEquals(Tab.SESSIONS, nav.tab)
        }
        rule.onNodeWithTag("empty").assertExists()
    }

    private fun showTabs() {
        rule.setContent {
            SwitchTetherTheme {
                CompositionLocalProvider(LocalPredictiveBack provides predictive.value) {
                    val tabs = rememberTabTransition(nav)
                    TabHost(nav, tabs) { Box(Modifier.size(96.dp).testTag(it.name)) }
                }
            }
        }
    }

    /** A page activity's content: a viewer that back closes before the page leaves. */
    private fun showPage() {
        rule.setContent {
            SwitchTetherTheme {
                CompositionLocalProvider(LocalPredictiveBack provides predictive.value) {
                    val back = rememberPredictiveBack(enabled = viewerOpen.value) { viewerOpen.value = false }
                    Box(Modifier.fillMaxSize().then(back), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(96.dp).testTag("page"))
                    }
                }
            }
        }
    }

    private fun startSwipe(edge: Int = BackEventCompat.EDGE_LEFT) {
        rule.runOnIdle {
            val width = rule.activity.window.decorView.width.toFloat()
            val startX = if (edge == BackEventCompat.EDGE_RIGHT) width else 0f
            val progressX = if (edge == BackEventCompat.EDGE_RIGHT) width - 150f else 150f
            dispatcher.dispatchOnBackStarted(BackEventCompat(startX, 200f, 0f, edge))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(progressX, 200f, 0.35f, edge))
        }
    }

    private fun leftOf(tag: String): Float = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
}
