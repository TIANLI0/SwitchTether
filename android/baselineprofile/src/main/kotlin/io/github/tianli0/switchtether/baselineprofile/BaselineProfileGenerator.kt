package io.github.tianli0.switchtether.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/release/generated/baselineProfiles/ with
 * `ANDROID_SERIAL=<device> ./gradlew :app:generateBaselineProfile` (android/DEV_NOTES.md).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start to the first screen; also feeds the startup profile (dex layout). */
    @Test
    fun startup() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        grantRuntimePermissions()
        pressHome()
        startActivityAndWait()
        awaitFirstScreen()
    }

    /** Sessions tab with list scrolling, then a long conversation scrolled through. */
    @Test
    fun sessionsAndConversation() {
        var paired = true
        rule.collect(packageName = TARGET_PACKAGE, maxIterations = 3, stableIterations = 2) {
            grantRuntimePermissions()
            pressHome()
            startActivityAndWait()
            paired = awaitFirstScreen()
            if (!paired) return@collect
            val list = openSessionsTab()
            scrollSessions(list)
            scrollConversation(openLongConversation(list))
        }
        assumeTrue("Not paired with a desktop: only the startup journey was profiled", paired)
    }
}
