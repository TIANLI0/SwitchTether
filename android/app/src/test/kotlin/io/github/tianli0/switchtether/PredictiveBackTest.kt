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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.ui.common.LocalPredictiveBack
import io.github.tianli0.switchtether.ui.common.rememberPredictiveBack
import io.github.tianli0.switchtether.ui.nav.AppNavHost
import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import io.github.tianli0.switchtether.ui.nav.rememberTabTransition
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val nav = NavViewModel()
    private val predictive = mutableStateOf(true)
    private val viewerOpen = mutableStateOf(false)
    private val dispatcher get() = rule.activity.onBackPressedDispatcher

    @Test
    fun tabRootPreviewsHomeAndOnlySwitchesOnCommit() {
        nav.push(Route.About)
        nav.select(Tab.WORKSPACE)
        showNavHost()
        startSwipe()
        rule.onNodeWithTag("About").assertExists()
        rule.runOnIdle {
            assertEquals(Tab.WORKSPACE, nav.tab)
            dispatcher.dispatchOnBackCancelled()
        }
        rule.onNodeWithTag("About").assertDoesNotExist()

        startSwipe()
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle {
            assertEquals(Tab.HOME, nav.tab)
            assertEquals(Route.About, nav.top)
        }
    }

    @Test
    fun changingTheSettingUpdatesTheAlreadyOpenPage() {
        predictive.value = false
        nav.push(Route.About)
        showNavHost()
        val restingLeft = leftOf("About")
        startSwipe()
        assertEquals(restingLeft, leftOf("About"), 0f)
        rule.runOnIdle { dispatcher.dispatchOnBackCancelled() }

        rule.runOnIdle { predictive.value = true }
        startSwipe()
        assertTrue(leftOf("About") > restingLeft)
        rule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        assertEquals(restingLeft, leftOf("About"), 0f)
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(null, nav.top) }
    }

    @Test
    fun rightEdgeMovesThePageLeftAndPopsOnCommit() {
        nav.push(Route.About)
        showNavHost()
        val restingLeft = leftOf("About")

        startSwipe(BackEventCompat.EDGE_RIGHT)
        assertTrue(leftOf("About") < restingLeft)
        rule.runOnIdle {
            assertEquals(Route.About, nav.top)
            dispatcher.dispatchOnBackCancelled()
        }
        assertEquals(restingLeft, leftOf("About"), 0f)

        startSwipe(BackEventCompat.EDGE_RIGHT)
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(null, nav.top) }
    }

    @Test
    fun anInPageSwipeFromTheRightAlsoFollowsTheFinger() {
        nav.push(Route.About)
        showNavHost()
        val restingLeft = leftOf("About")
        val root = rule.onNode(isRoot())

        root.performTouchInput {
            down(Offset(right - 8f, center.y))
            moveTo(Offset(right - 40f, center.y))
            moveTo(Offset(right * 0.45f, center.y))
        }
        assertTrue(leftOf("About") < restingLeft)
        root.performTouchInput { cancel() }
        assertEquals(restingLeft, leftOf("About"), 0f)
        rule.runOnIdle { assertEquals(Route.About, nav.top) }

        root.performTouchInput {
            down(Offset(right - 8f, center.y))
            moveTo(Offset(right - 40f, center.y))
            moveTo(Offset(right * 0.45f, center.y))
            up()
        }
        rule.runOnIdle { assertEquals(null, nav.top) }
    }

    @Test
    fun inPageBackPreviewsAndClosesTheViewerBeforePoppingTheRoute() {
        nav.push(Route.Files)
        viewerOpen.value = true
        showNavHost()
        val restingLeft = leftOf("Files")
        startSwipe()
        val viewerLeft = leftOf("Files")
        assertTrue(viewerLeft > restingLeft)
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle {
            assertEquals(false, viewerOpen.value)
            assertEquals(Route.Files, nav.top)
        }
    }

    private fun showNavHost() {
        rule.setContent {
            SwitchTetherTheme {
                CompositionLocalProvider(LocalPredictiveBack provides predictive.value) {
                    val tabs = rememberTabTransition(nav)
                    AppNavHost(nav, tabs, root = { Box(Modifier.size(96.dp).testTag(it.name)) }) { _, route ->
                        val back = rememberPredictiveBack(enabled = viewerOpen.value) { viewerOpen.value = false }
                        Box(Modifier.fillMaxSize().then(back), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(96.dp).testTag(route.toString()))
                        }
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
