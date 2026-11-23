package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.ui.settings.LicensesScreen
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An immersive page: its list starts below the title bar ([pageListPadding] adds the bar's height),
 * and scrolls under the frosted bar instead of being cut off at its edge. Screenshots land in
 * build/outputs/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w411dp-h891dp-xxhdpi")
class ImmersivePageTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theFirstItemStartsBelowTheBarAndTheListScrollsUnderIt() {
        rule.setContent { SwitchTetherTheme(ThemeMode.LIGHT) { LicensesScreen(onBack = {}) } }
        // At rest nothing is hidden under the bar: the first item is below it (bar height ≥ 48dp).
        val top = rule.onNodeWithText("built with these open source projects", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val minTop = 48f * rule.activity.resources.displayMetrics.density
        org.junit.Assert.assertTrue("first item starts below the title bar, was at $top", top >= minTop)
        screenshot("immersive-page-top")
        rule.onRoot().performTouchInput { swipeUp() }
        screenshot("immersive-page-scrolled")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onRoot() =
        onNode(androidx.compose.ui.test.isRoot())

    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(800)
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.autoAdvance = true
    }
}
