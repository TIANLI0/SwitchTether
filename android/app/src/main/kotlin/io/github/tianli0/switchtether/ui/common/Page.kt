package io.github.tianli0.switchtether.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.AppBarBlurOverlay
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.blur.LocalBackdrop
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.tianli0.coui17.patterns.BackArrow
import io.github.tianli0.coui17.patterns.LocalOnPageColor
import io.github.tianli0.coui17.patterns.LocalPageScrollBehavior
import io.github.tianli0.coui17.patterns.LocalPageTopInset
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.coui17.patterns.rememberPageListState as rememberCouiPageListState
import io.github.tianli0.coui17.patterns.rememberPageScrollState as rememberCouiPageScrollState

private class PageScrollSource {
    var offset by mutableStateOf<(() -> Float)?>(null)
}

private val LocalPageScrollSource = staticCompositionLocalOf<PageScrollSource?> { null }

@Composable
internal fun rememberPageListState(): LazyListState =
    rememberCouiPageListState().also { TrackPageList(it) }

@Composable
internal fun rememberPageScrollState(): ScrollState {
    val state = rememberCouiPageScrollState()
    TrackPageScroll(state) { state.value.toFloat() }
    return state
}

@Composable
internal fun TrackPageList(list: LazyListState) {
    TrackPageScroll(list) {
        if (list.firstVisibleItemIndex > 0) Float.POSITIVE_INFINITY else list.firstVisibleItemScrollOffset.toFloat()
    }
}

@Composable
private fun TrackPageScroll(key: Any, offset: () -> Float) {
    val source = LocalPageScrollSource.current ?: return
    DisposableEffect(source, key) {
        source.offset = offset
        onDispose { if (source.offset === offset) source.offset = null }
    }
}

/** A page title and optional pinned controls over the same COUI material as Sessions search. */
@Composable
internal fun Page(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    large: Boolean = onBack == null,
    immersive: Boolean = true,
    ownBarMaterial: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ProgressivePageLayout(modifier, immersive, drawMaterial = !ownBarMaterial, topBar = { behavior ->
        val nav: @Composable () -> Unit = { if (onBack != null) BackArrow(onBack) }
        if (large) {
            TopAppBar(title = title, subtitle = subtitle, color = Color.Transparent, scrollBehavior = behavior,
                showDivider = false, navigationIcon = nav, actions = actions)
        } else {
            SmallTopAppBar(title = title, subtitle = subtitle, color = Color.Transparent,
                showDivider = false, navigationIcon = nav, actions = actions)
        }
        header()
    }, content = content)
}

/** The captured body runs under the header. Scrollable bodies reserve [LocalPageTopInset]. */
@Composable
internal fun ProgressivePageLayout(
    modifier: Modifier = Modifier,
    immersive: Boolean = true,
    drawMaterial: Boolean = true,
    containerColor: Color = pageContainerColor(),
    topBar: @Composable ColumnScope.(ScrollBehavior) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val behavior = COUIScrollBehavior()
    val source = remember { PageScrollSource() }
    val color = containerColor
    val backdrop = if (immersive && drawMaterial && isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(color)
            drawContent()
        }
    } else null
    val fadeDistance = with(LocalDensity.current) { 24.dp.toPx() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = color,
        edgeToEdge = true,
        topBar = {
            val chrome: @Composable () -> Unit = { Column(Modifier.fillMaxWidth()) { topBar(behavior) } }
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                if (drawMaterial) {
                    AppBarBlurOverlay(
                        modifier = Modifier.fillMaxWidth(),
                        backdrop = backdrop,
                        containerColor = color,
                        platformRenderer = false,
                        scrollFraction = {
                            val offset = source.offset?.invoke() ?: -behavior.state.contentOffset
                            (offset / fadeDistance).coerceIn(0f, 1f)
                        },
                    ) { chrome() }
                } else {
                    chrome()
                }
            }
        },
    ) { inner ->
        val top = inner.calculateTopPadding()
        CompositionLocalProvider(
            LocalPageScrollBehavior provides behavior,
            LocalPageTopInset provides if (immersive) top else 0.dp,
            LocalPageScrollSource provides source,
            LocalOnPageColor provides true,
        ) {
            Column(
                Modifier.fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .padding(top = if (immersive) 0.dp else top),
                content = content,
            )
        }
    }
}
