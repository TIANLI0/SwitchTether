package io.github.tianli0.switchtether.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Screen changes with native motion, driven by [NavViewModel]:
 *  - push / pop inside a tab: shared X axis (the new page comes in from the trailing side, the old
 *    one drifts and fades the other way; reversed on pop);
 *  - switching tabs (and back to Home): fade through;
 *  - the system back gesture *seeks* the pop transition (predictive back): the previous screen is
 *    revealed with the finger, release commits, cancel springs back. At Home with nothing to pop the
 *    handler is disabled, so the system's "back to home" animation runs.
 * Each entry keeps its saved UI state (scroll position, fields) while it is in the back stack.
 */
@Composable
fun AnimatedNavHost(nav: NavViewModel, content: @Composable (NavEntry) -> Unit) {
    val target = nav.current
    val seek = remember { SeekableTransitionState(target) }
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(false) }

    // Taps, deep links and programmatic navigation animate normally.
    LaunchedEffect(target) {
        if (!gesture && seek.targetState != target) seek.animateTo(target)
    }

    PredictiveBackHandler(enabled = nav.previous != null) { events ->
        val to = nav.previous ?: return@PredictiveBackHandler
        gesture = true
        try {
            events.collect { e -> seek.seekTo(e.progress, to) }
            nav.back() // committed
            seek.animateTo(to)
        } catch (c: CancellationException) {
            // Cancelled gesture: return to the screen we started from.
            scope.launch { seek.animateTo(seek.currentState) }
            throw c
        } finally {
            gesture = false
        }
    }

    val transition = rememberTransition(seek, label = "nav")
    transition.AnimatedContent(transitionSpec = { navTransform() }, contentKey = { it.key }) { entry ->
        holder.SaveableStateProvider(entry.key) { content(entry) }
    }
}

private const val DURATION = 320

private fun AnimatedContentTransitionScope<NavEntry>.navTransform(): ContentTransform {
    val from = initialState
    val to = targetState
    if (from.tab != to.tab) {
        // Fade through: out quickly, in with a slight scale-up.
        return (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f)) togetherWith
            fadeOut(tween(90))
    }
    val forward = to.depth > from.depth
    val dir = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { w -> dir * w / 3 } +
            fadeIn(tween(DURATION / 2, delayMillis = DURATION / 6)),
        initialContentExit = slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { w -> -dir * w / 3 } +
            fadeOut(tween(DURATION / 2)),
        // The deeper page is drawn on top in both directions.
        targetContentZIndex = if (forward) 1f else -1f,
    )
}
