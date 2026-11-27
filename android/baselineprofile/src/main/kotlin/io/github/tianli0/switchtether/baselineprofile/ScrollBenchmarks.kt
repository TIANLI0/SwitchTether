package io.github.tianli0.switchtether.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing of the Sessions list and a long conversation; needs a paired desktop (skipped otherwise). */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ScrollBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun sessionsListCompilationNone() = sessionsList(CompilationMode.None())

    @Test
    fun sessionsListCompilationBaselineProfile() = sessionsList(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun conversationCompilationNone() = conversation(CompilationMode.None())

    @Test
    fun conversationCompilationBaselineProfile() = conversation(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun sessionsList(mode: CompilationMode) = measure(mode) { scrollSessions(openSessionsTab()) }

    private fun conversation(mode: CompilationMode) = measure(mode) { scrollConversation(openLongConversation(openSessionsTab())) }

    private fun measure(mode: CompilationMode, journey: androidx.benchmark.macro.MacrobenchmarkScope.() -> Unit) {
        var paired = true
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = mode,
            // No startupMode: with COLD the process is killed after setupBlock, i.e. after the
            // launch below. Each iteration still starts from a fresh process.
            startupMode = null,
            iterations = 5,
            setupBlock = {
                grantRuntimePermissions()
                killProcess()
                startActivityAndWait()
                paired = awaitFirstScreen()
            },
        ) {
            if (paired) journey()
        }
        assumeTrue("Not paired with a desktop", paired)
    }
}
