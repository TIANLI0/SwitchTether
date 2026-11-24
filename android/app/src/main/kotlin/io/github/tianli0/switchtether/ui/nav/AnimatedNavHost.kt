package io.github.tianli0.switchtether.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Screen changes with native motion, driven by [NavViewModel]:
 *  - push / pop inside a tab: ColorOS's activity slide (`Animation.COUI.Activity`): the new page
 *    comes in over the whole width, opaque; the page underneath moves 30% the other way and dims
 *    under a black scrim to 0.5, still visible. Pop runs it back;
 *  - switching tabs (and back to Home): a short cross-fade;
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

    // Android's standard predictive back (the system's cross-activity look): while the finger
    // drags, the page shrinks into a rounded card that leans toward the swipe edge, and the previous
    // page shows behind it. The transition is parked at its start so both pages are composed; the
    // card is driven by backProgress. Release shrinks and fades the card away, then pops; cancel
    // springs the card back.
    val backProgress = remember { Animatable(0f) }
    val backExit = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = nav.previous != null) { events ->
        val to = nav.previous ?: return@PredictiveBackHandler
        var dragged = false
        var committed = false
        try {
            events.collect { e ->
                if (!dragged) {
                    dragged = true
                    gesture = true
                    seek.seekTo(0f, to)
                }
                backEdge = e.swipeEdge
                backProgress.snapTo(e.progress)
            }
            committed = true
            if (!dragged) {
                // The back key (or a back without a drag) sends no progress: the ordinary pop.
                nav.back()
                return@PredictiveBackHandler
            }
            // Committed: the card finishes shrinking and fades out, then the pop completes.
            backExit.animateTo(1f, tween(BACK_EXIT_MS, easing = io.github.suqi8.coui.kmp.anim.COUIEase))
            nav.back()
            seek.snapTo(to)
        } catch (c: CancellationException) {
            if (dragged) {
                scope.launch {
                    backProgress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
                    seek.snapTo(seek.currentState)
                    gesture = false
                }
            }
            throw c
        } finally {
            if (committed) {
                gesture = false
                backProgress.snapTo(0f)
                backExit.snapTo(0f)
            }
        }
    }

    val transition = rememberTransition(seek, label = "nav")
    val pageColor = io.github.tianli0.coui17.patterns.pageContainerColor()
    transition.AnimatedContent(transitionSpec = { navTransform(linear = gesture) }, contentKey = { it.key }) { entry ->
        // The page under a push (and the one revealed by a pop) dims under black, as the COUI
        // activity animation's alpha 1 -> 0.5 over the window's black does.
        val other = if (entry.key == transition.targetState.key) transition.currentState else transition.targetState
        val lower = other.tab == entry.tab && entry.depth < other.depth
        val animatedScrim by this.transition.animateFloat(
            transitionSpec = { tween(DURATION, easing = io.github.suqi8.coui.kmp.anim.COUIEase) },
            label = "nav-scrim",
        ) { state -> if (lower && state != EnterExitState.Visible) SCRIM_ALPHA else 0f }
        // During the back gesture: the page being popped is the card; the page under it lightens
        // as the card shrinks.
        val popping = gesture && entry.key == transition.currentState.key && entry.key != transition.targetState.key
        val revealed = gesture && entry.key == transition.targetState.key && entry.key != transition.currentState.key
        val scrim = if (revealed) BACK_SCRIM * (1f - backProgress.value) * (1f - backExit.value) else animatedScrim
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (popping) {
                        Modifier.graphicsLayer {
                            val p = backProgress.value
                            val x = backExit.value
                            val scale = 1f - BACK_SHRINK * p - BACK_EXIT_SHRINK * x
                            scaleX = scale
                            scaleY = scale
                            val shift = size.width * BACK_SHIFT_FRACTION * p
                            translationX = if (backEdge == BackEventCompat.EDGE_RIGHT) -shift else shift
                            alpha = 1f - x
                            shape = RoundedCornerShape(BACK_CORNER * maxOf(p, x))
                            clip = true
                        }
                    } else {
                        Modifier
                    },
                )
                // Opaque: a full-width slide must never show two pages through each other.
                .background(pageColor)
                .drawWithContent {
                    drawContent()
                    if (scrim > 0f) drawRect(Color.Black.copy(alpha = scrim))
                },
        ) {
            holder.SaveableStateProvider(entry.key) { content(entry) }
        }
    }
}

/**
 * `coui_open_slide_*` / `coui_close_slide_*` run 350ms; that felt slow on the device, so the same
 * curves run in 280ms (an in-app page change, not a new window).
 */
private const val DURATION = 280

/** The page underneath ends at alpha 0.5 over black. */
private const val SCRIM_ALPHA = 0.5f

/** Android's predictive back card: 90 % at the end of the drag, leaning toward the swipe edge. */
private const val BACK_SHRINK = 0.1f
private const val BACK_SHIFT_FRACTION = 0.04f
private val BACK_CORNER = 32.dp

/** The page revealed behind the card starts dimmed and lightens with the drag. */
private const val BACK_SCRIM = 0.25f

/** After release the card shrinks a little further and fades out. */
private const val BACK_EXIT_SHRINK = 0.06f
private const val BACK_EXIT_MS = 180

private val OpenEnter = CubicBezierEasing(0.3f, 0.1f, 0.3f, 1f)
private val OpenExit = CubicBezierEasing(0.3f, 0.15f, 0.3f, 1f)
private val CloseExit = CubicBezierEasing(0.25f, 0.1f, 0.3f, 1f)
private val CloseEnter = CubicBezierEasing(0.3f, 0.26f, 0.4f, 1f)

/**
 * [linear]: while the back gesture seeks the pop, progress maps straight onto position (an eased
 * curve made the page jump at the start of the drag); release and cancel finish on the same spec.
 */
private fun AnimatedContentTransitionScope<NavEntry>.navTransform(linear: Boolean): ContentTransform {
    val from = initialState
    val to = targetState
    if (linear) {
        // The back gesture draws the card itself (graphicsLayer in AnimatedNavHost): nothing moves
        // here, the previous page waits underneath.
        return ContentTransform(
            targetContentEnter = androidx.compose.animation.EnterTransition.None,
            initialContentExit = androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished,
            targetContentZIndex = -1f,
        )
    }
    if (from.tab != to.tab) {
        // ColorOS tab switches have no scale: a short cross-fade.
        return fadeIn(tween(TAB_FADE)) togetherWith fadeOut(tween(TAB_FADE))
    }
    val forward = to.depth > from.depth
    fun spec(easing: androidx.compose.animation.core.Easing) =
        tween<androidx.compose.ui.unit.IntOffset>(DURATION, easing = if (linear) LinearEasing else easing)
    return if (forward) {
        ContentTransform(
            targetContentEnter = slideInHorizontally(spec(OpenEnter)) { w -> w },
            initialContentExit = slideOutHorizontally(spec(OpenExit)) { w -> -(w * 0.3f).toInt() },
            targetContentZIndex = 1f,
        )
    } else {
        ContentTransform(
            targetContentEnter = slideInHorizontally(spec(CloseEnter)) { w -> -(w * 0.3f).toInt() },
            initialContentExit = slideOutHorizontally(spec(CloseExit)) { w -> w },
            // The deeper page is drawn on top in both directions.
            targetContentZIndex = -1f,
        )
    }
}

/** Tab cross-fade. */
private const val TAB_FADE = 150
