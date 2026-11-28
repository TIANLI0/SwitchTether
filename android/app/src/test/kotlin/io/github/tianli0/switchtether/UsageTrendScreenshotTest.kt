package io.github.tianli0.switchtether

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.usage.UsageRangePreset
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.manage.UsageContent
import io.github.tianli0.switchtether.ui.manage.UsageMetric
import io.github.tianli0.switchtether.ui.manage.UsageState
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The usage trend card: bars for today … 30 days, the calendar heatmap for "全部" (as on the
 * desktop), rendered into build/outputs/screenshots/trend-*.png. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class UsageTrendScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 28)

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1200)
        val v = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.autoAdvance = true
    }

    private fun day(d: LocalDate, tokens: Long) = DailyUsage(
        date = d.atStartOfDay(zone).toOffsetDateTime().toString(),
        requests = tokens / 9_000,
        costUsd = tokens / 1_000_000.0 * 1.7,
        tokens = tokens,
        inputTokens = tokens / 20,
        outputTokens = tokens / 25,
        cacheReadTokens = tokens * 8 / 10,
    )

    /** A believable month: quiet weekends, a busy middle, today half done. */
    private fun days(n: Int): List<DailyUsage> = (0 until n).map { i ->
        val d = today.minusDays((n - 1 - i).toLong())
        val weekend = d.dayOfWeek.value >= 6
        val base = if (weekend) 900_000L else 4_000_000L + (i * 7_919 % 11) * 600_000L
        day(d, if (i % 9 == 4) 0 else base)
    }

    private fun state(preset: UsageRangePreset, trend: List<DailyUsage>, from: LocalDate, metric: UsageMetric = UsageMetric.TOKENS, hourlyTo: Long? = null) = UsageState(
        preset = preset,
        metric = metric,
        fromSec = from.atStartOfDay(zone).toEpochSecond(),
        toSec = hourlyTo ?: today.atTime(15, 0).atZone(zone).toEpochSecond(),
        zone = zone,
        summary = Loadable.Loading,
        trend = Loadable.Ready(trend),
        models = Loadable.Ready(emptyList()),
        providers = Loadable.Ready(emptyList()),
        logs = null,
        projects = null,
    )

    private fun page(st: UsageState) {
        rule.setContent {
            SwitchTetherTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(pageContainerColor())) { UsageContent(st, onRetry = {}) }
            }
        }
        rule.onNodeWithTag("usage-list").performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) }
    }

    /** Puts a finger down at [at] (fractions of the node) and keeps it there past the long-press delay. */
    private fun hold(tag: String, at: Offset) {
        rule.onNodeWithTag(tag).performTouchInput { down(Offset(left + width * at.x, top + height * at.y)) }
        rule.mainClock.advanceTimeBy(800)
    }

    private fun release(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    @Test
    fun weekAsBars() {
        page(state(UsageRangePreset.DAYS_7, days(7), today.minusDays(6)))
        rule.onNodeWithTag("usage-bars").assertIsDisplayed()
        shot("trend-7d")
        // No details until a bar is held: then its numbers float over the chart.
        rule.onNodeWithTag("usage-heat-tooltip").assertDoesNotExist()
        hold("usage-bars", Offset(0.62f, 0.7f))
        rule.onNodeWithTag("usage-heat-tooltip").assertIsDisplayed()
        shot("trend-7d-held")
        release("usage-bars")
        rule.onNodeWithTag("usage-heat-tooltip").assertDoesNotExist()
        // A bar on the start side: the bubble goes to its end.
        hold("usage-bars", Offset(0.2f, 0.7f))
        shot("trend-7d-held-start")
        release("usage-bars")
    }

    @Test
    fun monthAsBarsInCost() {
        page(state(UsageRangePreset.DAYS_30, days(30), today.minusDays(29), metric = UsageMetric.COST))
        rule.onNodeWithTag("usage-bars").assertIsDisplayed()
        shot("trend-30d-cost")
    }

    @Test
    fun todayAsHourlyBars() {
        val hours = (0 until 16).map { h ->
            val t = today.atTime(h, 0).atZone(zone)
            val tokens = if (h in 9..15) 300_000L + h * 40_000L else if (h == 1) 80_000L else 0L
            DailyUsage(t.toOffsetDateTime().toString(), tokens / 9_000, tokens / 1_000_000.0 * 1.7, tokens)
        }
        page(state(UsageRangePreset.TODAY, hours, today, hourlyTo = today.atTime(15, 30).atZone(zone).toEpochSecond()))
        rule.onNodeWithTag("usage-bars").assertIsDisplayed()
        shot("trend-today")
    }

    @Test
    fun allStaysAHeatmap() {
        page(state(UsageRangePreset.ALL, days(120), today.minusDays(119)))
        rule.onNodeWithTag("usage-heatmap").performScrollTo().assertIsDisplayed()
        shot("trend-all")
        hold("usage-heatmap", Offset(0.85f, 0.4f))
        rule.onNodeWithTag("usage-heat-tooltip").assertIsDisplayed()
        shot("trend-all-held")
        release("usage-heatmap")
    }
}
