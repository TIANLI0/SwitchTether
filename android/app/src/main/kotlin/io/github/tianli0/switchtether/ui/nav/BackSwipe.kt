package io.github.tianli0.switchtether.ui.nav

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * NavDisplay recognizes an in-page drag in the reading direction. Add the opposite direction
 * near the other edge for devices that only deliver a committed system back. This listens to
 * touches inside the app and uses the same dispatcher as the system, including page handlers.
 */
@Composable
internal fun rememberOppositeBackSwipe(page: Any, rtl: Boolean): Modifier {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return Modifier
    val input = remember(dispatcher) { DirectNavigationEventInput() }
    DisposableEffect(dispatcher, input) {
        dispatcher.addInput(input)
        onDispose { dispatcher.removeInput(input) }
    }

    return Modifier.pointerInput(input, page, rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat().coerceAtLeast(1f)
            val fromEdge = if (rtl) down.position.x else width - down.position.x
            if (fromEdge > BACK_SWIPE_AREA.toPx()) return@awaitEachGesture

            val sign = if (rtl) 1f else -1f
            // The Main pass gives scrollable content the first chance to claim the gesture.
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (sign * overSlop > 0f) change.consume()
            } ?: return@awaitEachGesture

            val edge = if (rtl) NavigationEvent.EDGE_LEFT else NavigationEvent.EDGE_RIGHT
            var progress = 0f
            var commit = false
            input.backStarted(down.backEvent(edge, progress))
            try {
                val released = horizontalDrag(start.id) { change ->
                    progress = (sign * (change.position.x - start.position.x) / width).coerceIn(0f, 1f)
                    change.consume()
                    input.backProgressed(change.backEvent(edge, progress))
                }
                commit = released && progress >= BACK_SWIPE_COMMIT_PROGRESS
            } finally {
                if (commit) input.backCompleted() else input.backCancelled()
            }
        }
    }
}

private fun PointerInputChange.backEvent(edge: Int, progress: Float): NavigationEvent =
    NavigationEvent(
        swipeEdge = edge,
        progress = progress,
        touchX = position.x,
        touchY = position.y,
        frameTimeMillis = uptimeMillis,
    )

private val BACK_SWIPE_AREA = 72.dp
private const val BACK_SWIPE_COMMIT_PROGRESS = 0.28f
