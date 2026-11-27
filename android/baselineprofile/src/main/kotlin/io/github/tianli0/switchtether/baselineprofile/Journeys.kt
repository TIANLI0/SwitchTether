package io.github.tianli0.switchtether.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * User journeys shared by the Baseline Profile generator and the Macrobenchmarks.
 *
 * The Sessions and conversation journeys need a paired desktop with sessions (android/DEV_NOTES.md
 * "Performance": sandboxed dev_server + one manual pairing). Without one the app opens on
 * onboarding and only the cold-start journey runs. Elements are found by their Compose test tags
 * (exposed as resource ids by the app root) and the tab label in English or Simplified Chinese.
 */
const val TARGET_PACKAGE = "io.github.tianli0.switchtether"

/** Title of the long synthetic conversation written by the sandbox generator. */
private const val LONG_CHAT_TITLE = "Long benchmark conversation"

private const val TIMEOUT_MS = 15_000L

/**
 * Permissions whose system dialogs would otherwise cover the first screen. `pm grant` fails
 * harmlessly for a permission the platform does not know.
 */
fun MacrobenchmarkScope.grantRuntimePermissions() {
    listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK").forEach {
        runCatching { device.executeShellCommand("pm grant $packageName $it") }
    }
}

/** Waits for Home (paired) or onboarding (not paired); true when Home is shown. */
fun MacrobenchmarkScope.awaitFirstScreen(): Boolean {
    device.wait(Until.hasObject(By.res(Pattern.compile("home-list|scan|pairing-link"))), TIMEOUT_MS)
    return device.hasObject(By.res("home-list"))
}

/** Switches to the Sessions tab and returns its list. */
fun MacrobenchmarkScope.openSessionsTab(): UiObject2 {
    // The bottom navigation label: the lowest match on screen is the tab, not a section title.
    val tab = device.findObjects(By.text(Pattern.compile("Sessions|会话")))
        .maxByOrNull { it.visibleBounds.top }
        ?: error("Sessions tab not found")
    tab.click()
    return device.wait(Until.findObject(By.res("session-list")), TIMEOUT_MS)
        ?: error("session-list not shown (no sessions on the paired desktop?)")
}

/**
 * Gestures stay clear of what floats over a list's bottom edge (the navigation capsule, the
 * composer): a swipe starting on those does not scroll the list.
 */
private fun MacrobenchmarkScope.keepGesturesClear(list: UiObject2) {
    val w = device.displayWidth / 5
    list.setGestureMargins(w, device.displayHeight / 8, w, device.displayHeight / 4)
}

fun MacrobenchmarkScope.scrollSessions(list: UiObject2) {
    keepGesturesClear(list)
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    list.fling(Direction.UP)
    device.waitForIdle()
}

/** Opens the long conversation from the Sessions list and returns its event list. */
fun MacrobenchmarkScope.openLongConversation(list: UiObject2): UiObject2 {
    val title = By.textContains(LONG_CHAT_TITLE)
    keepGesturesClear(list)
    if (!device.hasObject(title)) list.scrollUntil(Direction.DOWN, Until.hasObject(title))
    if (!device.hasObject(title)) list.scrollUntil(Direction.UP, Until.hasObject(title))
    val row = device.wait(Until.findObject(title), TIMEOUT_MS)
        ?: error("\"$LONG_CHAT_TITLE\" is not in the Sessions list")
    row.click()
    return device.wait(Until.findObject(By.res("event-list")), TIMEOUT_MS)
        ?: error("event-list not shown")
}

/** Scrolls the conversation back through older events, then forward again. */
fun MacrobenchmarkScope.scrollConversation(events: UiObject2) {
    // Let the latest page render before scrolling.
    device.wait(Until.hasObject(By.res("event-list").hasDescendant(By.textContains("Turn"))), TIMEOUT_MS)
    keepGesturesClear(events)
    repeat(4) {
        events.fling(Direction.UP)
        device.waitForIdle()
    }
    repeat(2) {
        events.fling(Direction.DOWN)
        device.waitForIdle()
    }
}
