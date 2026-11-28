package io.github.tianli0.switchtether.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import io.github.suqi8.coui.kmp.anim.COUIEase
import io.github.suqi8.coui.kmp.nav.core.NavCornerClipMode
import io.github.suqi8.coui.kmp.nav.core.NavDisplay
import io.github.suqi8.coui.kmp.nav.core.NavDisplayEffects
import io.github.suqi8.coui.kmp.nav.core.rememberNavSystemCornerRadius
import io.github.suqi8.coui.kmp.nav.transition.NavMotion
import io.github.suqi8.coui.kmp.nav.transition.NavSettleSpec
import io.github.suqi8.coui.kmp.nav.transition.NavSwipeDirection
import io.github.suqi8.coui.kmp.nav.transition.NavSwipeEdge
import io.github.suqi8.coui.kmp.nav.transition.NavTransition
import io.github.suqi8.coui.kmp.nav.transition.navGraphicsTransition
import io.github.tianli0.switchtether.ui.common.LocalPredictiveBack
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One tab transition for both panes, driven by the finger when going back from a tab root. */
@Composable
internal fun rememberTabTransition(nav: NavViewModel): Transition<Tab> {
    val state = remember { SeekableTransitionState(nav.tab) }
    val transition = rememberTransition(state, label = "tab")
    val scope = rememberCoroutineScope()
    val predictive = LocalPredictiveBack.current
    val returnsHome = nav.tab != Tab.HOME && nav.top == null

    LaunchedEffect(nav.tab) { state.animateTo(nav.tab) }
    // Register before the pages so their own back actions take precedence.
    BackHandler(enabled = returnsHome && !predictive) { nav.back() }
    PredictiveBackHandler(enabled = returnsHome && predictive) { events ->
        val from = nav.tab
        if (from == Tab.HOME || nav.top != null) return@PredictiveBackHandler
        try {
            events.collect {
                if (nav.tab == from && nav.top == null) state.seekTo(it.progress.coerceIn(0f, 1f), Tab.HOME)
            }
            if (nav.tab == from && nav.top == null) nav.back()
        } finally {
            // A cancelled gesture leaves the selected tab intact and reverses its preview.
            if (nav.tab == from) scope.launch { state.animateTo(from) }
        }
    }
    return transition
}

@Composable
internal fun TabCrossfade(transition: Transition<Tab>, activeTab: Tab, content: @Composable (Tab) -> Unit) {
    transition.Crossfade(animationSpec = tween(TAB_FADE, easing = COUIEase)) { tab ->
        // The Home preview and the outgoing tab can both be composed, with their own back stacks.
        // Only the selected tab may handle back until the gesture commits.
        val owner = rememberNavigationEventDispatcherOwner(enabled = tab == activeTab)
        val semantics = if (tab == activeTab) Modifier else Modifier.semantics { hideFromAccessibility() }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            Box(semantics) { content(tab) }
        }
    }
}

/**
 * The tabs and their back stacks, rendered by coui-nav's `NavDisplay` (one per tab, cross-faded on
 * a tab switch; each tab keeps its pages' saved UI state while another tab shows).
 *
 * Push, pop, the system's predictive back gesture and in-page swipes all drive the
 * same position, so the motion is one continuous ColorOS activity slide ([colorOsSlide]): the page
 * follows the finger while the gesture runs, release finishes the pop from where the finger left it,
 * cancel slides it back. The swipe is the fallback for systems that do not hand the app the
 * gesture's progress; it only takes drags the page itself does not (horizontal scrollers keep
 * theirs). Settings → Appearance → predictive back off: no swipe, and back is a plain pop.
 *
 * At the Home root the system handles back to the launcher.
 */
@Composable
internal fun AppNavHost(nav: NavViewModel, tabs: Transition<Tab>, root: @Composable (Tab) -> Unit, page: @Composable (Tab, Route) -> Unit) {
    val predictive = LocalPredictiveBack.current
    // Pages keep using the activity's ViewModels (a pane and its full-screen page share one), not
    // NavDisplay's per-entry stores.
    val viewModels = LocalViewModelStoreOwner.current
    val tabStates = rememberSaveableStateHolder()
    val pageColor = io.github.tianli0.coui17.patterns.pageContainerColor()
    val corner = rememberNavSystemCornerRadius()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val transition = remember(predictive, rtl) { colorOsSlide(swipe = predictive, rtl = rtl) }
    val effects = remember(corner, pageColor) {
        NavDisplayEffects(
            enableCornerClip = true,
            cornerClipRadius = corner,
            cornerClipMode = NavCornerClipMode.All,
            dimAmount = SCRIM_ALPHA,
            backdropColor = pageColor,
        )
    }

    TabCrossfade(tabs, nav.tab) { tab ->
        tabStates.SaveableStateProvider("tab/${tab.name}") {
            val stack = nav.backStack(tab)
            val backSwipe = if (predictive && tab == nav.tab && stack.size > 1) {
                rememberOppositeBackSwipe(stack.last(), rtl)
            } else {
                Modifier
            }
            NavDisplay(
                backStack = stack,
                modifier = backSwipe,
                onBack = { nav.pop(tab) },
                transition = transition,
                effects = effects,
            ) {
                entry<Page> { p ->
                    val content: @Composable () -> Unit = {
                        // Opaque: a sliding page never shows the one under it through itself.
                        Box(Modifier.fillMaxSize().background(pageColor)) {
                            if (p.route == null) root(p.tab) else page(p.tab, p.route)
                        }
                    }
                    if (p.route != null) {
                        // Registered inside the page, so it wins over NavDisplay's gesture handler;
                        // NavDisplay enables it only while this page is on top.
                        BackHandler(enabled = !LocalPredictiveBack.current) { nav.pop(p.tab) }
                    }
                    if (viewModels != null) CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels, content = content) else content()
                }
            }
        }
    }
}

/**
 * ColorOS's activity slide (`Animation.COUI.Activity`) as a function of the page's depth `d`:
 * `-1..0` the page coming in over the whole width (opaque), `0..1` the page underneath moving 30 %
 * the other way while NavDisplay dims it under black to [SCRIM_ALPHA]. Linear in `d`, so a gesture
 * follows the finger from either edge. Push / pop without a gesture follow the layout direction
 * and run [PAGE_MS] on the COUI open curve.
 */
internal fun colorOsSlide(swipe: Boolean, rtl: Boolean): NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    dismissDirection = when {
        !swipe -> NavSwipeDirection.None
        rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    },
    motion = NavMotion(programmatic = NavSettleSpec.Tween(PAGE_MS, PageEasing)),
) { scope ->
    val width = scope.layoutSize.width.toFloat()
    val d = scope.relativeDepth
    val sign = when (scope.gesture?.swipeEdge) {
        NavSwipeEdge.Left -> 1f
        NavSwipeEdge.Right -> -1f
        else -> if (rtl) -1f else 1f
    }
    translationX = if (d <= 0f) {
        // Whole pixels: the corner-clipped edge does not shimmer at fractional offsets.
        (sign * (-d).coerceIn(0f, 1f) * width).roundToInt().toFloat()
    } else {
        -sign * d.coerceIn(0f, 1f) * width * UNDER_SHIFT
    }
}

/**
 * `coui_open_slide_*` runs 350ms; that felt slow on the device, so the same curve runs in 280ms
 * (an in-app page change, not a new window).
 */
private const val PAGE_MS = 280
private val PageEasing = CubicBezierEasing(0.3f, 0.1f, 0.3f, 1f)

/** The page underneath moves 30 % of the width and ends at alpha 0.5 over black. */
private const val UNDER_SHIFT = 0.3f
private const val SCRIM_ALPHA = 0.5f

/** Tab cross-fade. */
private const val TAB_FADE = 150
