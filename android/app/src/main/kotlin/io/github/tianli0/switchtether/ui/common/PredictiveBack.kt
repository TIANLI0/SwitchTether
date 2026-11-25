package io.github.tianli0.switchtether.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Predictive back for a view that replaces its content in place (a file opened over its folder, a
 * Git diff over the status, a folder over its parent, a step of a flow) instead of pushing a page on
 * the [io.github.tianli0.switchtether.ui.nav.AnimatedNavHost] stack.
 *
 * While the back gesture runs, the content follows the finger as Android's in-app predictive back
 * does: it shrinks to 90 %, rounds its corners and moves a little toward the swipe edge. Release
 * runs [onBack]; the content that replaces it grows back to full size from where the gesture left
 * off. Cancel springs back. The back key (no drag) calls [onBack] at once.
 *
 * Returns the modifier to put on the container whose content [onBack] replaces.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean = true, onBack: () -> Unit): Modifier {
    val progress = remember { Animatable(0f) }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { e ->
                edge = e.swipeEdge
                progress.snapTo(e.progress)
            }
            currentOnBack()
            // What replaced the content grows into place from the gesture's end state.
            scope.launch { progress.animateTo(0f, tween(BACK_SETTLE_MS)) }
        } catch (c: CancellationException) {
            scope.launch { progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
            throw c
        }
    }
    return Modifier.graphicsLayer {
        val p = io.github.tianli0.switchtether.ui.nav.backEase(progress.value)
        if (p <= 0f) return@graphicsLayer
        val scale = 1f - MAX_SHRINK * p
        scaleX = scale
        scaleY = scale
        val shift = MAX_SHIFT.toPx() * p
        translationX = if (edge == BackEventCompat.EDGE_RIGHT) -shift else shift
        shape = RoundedCornerShape(MAX_CORNER * p)
        clip = true
    }
}

/** Android's in-app predictive back: the content scales to 90 % at the end of the drag. */
private const val MAX_SHRINK = 0.1f
private val MAX_SHIFT = 16.dp
private val MAX_CORNER = 32.dp
private const val BACK_SETTLE_MS = 220

/**
 * [io.github.tianli0.coui17.patterns.FullScreenDialog] (a viewer in its own window) with the same
 * predictive back: the dialog's content shrinks with the gesture and the window closes on release.
 */
@Composable
fun AppFullScreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    io.github.tianli0.coui17.patterns.FullScreenDialog(onDismissRequest = onDismissRequest) {
        val back = rememberPredictiveBack(onBack = onDismissRequest)
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.then(back),
        ) { content() }
    }
}
