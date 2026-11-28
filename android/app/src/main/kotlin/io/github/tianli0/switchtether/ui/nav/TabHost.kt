package io.github.tianli0.switchtether.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import io.github.suqi8.coui.kmp.anim.COUIEase
import io.github.tianli0.switchtether.ui.common.LocalPredictiveBack
import kotlinx.coroutines.launch

/**
 * One tab transition for the whole window. Back from a tab root (nothing chosen in its detail pane)
 * previews Home with a cross-fade driven by the gesture's progress when the system hands it over.
 */
@Composable
internal fun rememberTabTransition(nav: NavViewModel): Transition<Tab> {
    val state = remember { SeekableTransitionState(nav.tab) }
    val transition = rememberTransition(state, label = "tab")
    val scope = rememberCoroutineScope()
    val predictive = LocalPredictiveBack.current
    val returnsHome = nav.tab != Tab.HOME && nav.selection == null

    LaunchedEffect(nav.tab) { state.animateTo(nav.tab) }
    // Register before the panes so their own back actions take precedence.
    BackHandler(enabled = returnsHome && !predictive) { nav.back() }
    PredictiveBackHandler(enabled = returnsHome && predictive) { events ->
        val from = nav.tab
        if (from == Tab.HOME || nav.selection != null) return@PredictiveBackHandler
        try {
            events.collect {
                if (nav.tab == from && nav.selection == null) state.seekTo(it.progress.coerceIn(0f, 1f), Tab.HOME)
            }
            if (nav.tab == from && nav.selection == null) nav.back()
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
        // The Home preview and the outgoing tab can both be composed. Only the selected tab may
        // handle back until the gesture commits.
        val owner = rememberNavigationEventDispatcherOwner(enabled = tab == activeTab)
        val semantics = if (tab == activeTab) Modifier else Modifier.semantics { hideFromAccessibility() }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            Box(semantics) { content(tab) }
        }
    }
}

/** The tab roots, cross-faded on a tab switch; each keeps its saved UI state while another tab shows. */
@Composable
internal fun TabHost(nav: NavViewModel, tabs: Transition<Tab>, root: @Composable (Tab) -> Unit) {
    val tabStates = rememberSaveableStateHolder()
    TabCrossfade(tabs, nav.tab) { tab ->
        tabStates.SaveableStateProvider("tab/${tab.name}") { root(tab) }
    }
}

/**
 * A tablet's detail pane: what was chosen from the list, or [empty]. Back clears the choice; pages
 * opened from inside it are activities of their own.
 */
@Composable
internal fun DetailPane(nav: NavViewModel, empty: @Composable (Tab) -> Unit, page: @Composable (Tab, Route) -> Unit) {
    val tab = nav.tab
    val selection = nav.selection
    BackHandler(enabled = selection != null) { nav.back() }
    val states = rememberSaveableStateHolder()
    Crossfade(tab to selection, animationSpec = tween(TAB_FADE, easing = COUIEase), label = "detail") { (t, r) ->
        if (r == null) {
            empty(t)
        } else {
            states.SaveableStateProvider("detail/${t.name}/${Route.encode(r)}") { page(t, r) }
        }
    }
}

/** Tab and detail cross-fade. */
private const val TAB_FADE = 150
