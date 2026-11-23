package io.github.tianli0.switchtether.ui.common

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.AppBarBlurOverlayDefaults
import io.github.suqi8.coui.kmp.basic.BindScrollToTop
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.couiTopAppBarActionItem
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import io.github.tianli0.switchtether.R

/**
 * Side gutter of loose text on a page or in a panel (a note under a group, an intro, an empty hint):
 * the 16dp card margin plus the 16dp inside it, so the text lines up with `SmallTitle` and with the
 * titles of the rows in the cards around it.
 */
val TextGutter = 32.dp

/** Space the floating bottom navigation bar occupies over page content (set by the shell). */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/**
 * Height of the page's title bar (incl. the status bar) when the page is [Page]`(immersive = true)`:
 * the list then starts at the top of the screen and scrolls under the glass bar, so its first item
 * must start this far down ([pageListPadding] adds it). 0 on other pages.
 */
val LocalPageTopInset = compositionLocalOf { 0.dp }

/** The current page's collapsing-title scroll behavior (null on pages without one). */
val LocalPageScrollBehavior = compositionLocalOf<ScrollBehavior?> { null }

/** ColorOS 17 page background (cards use `surfaceContainer` on top of it). */
@Composable
fun pageContainerColor(): Color = AppBarBlurOverlayDefaults.containerColor()

/**
 * Modifier for a page's main scrolling list: COUI overscroll + nested scroll into the collapsing
 * large title (the coui17 gallery's `pageScrollModifiers`).
 */
@Composable
fun Modifier.pageScroll(): Modifier {
    val behavior = LocalPageScrollBehavior.current
    return this
        .scrollEndHaptic()
        .overScrollVertical()
        .then(if (behavior != null) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier)
        .fillMaxHeight()
}

/**
 * State for a page's main list, bound to the COUI17 scroll-to-top controller of the page's
 * `Scaffold`: tapping the top app bar (and, on ColorOS, the status bar) scrolls it back to the top
 * and re-expands the collapsing title. Not for bottom-following lists (chat, terminal).
 */
@Composable
fun rememberPageListState(): LazyListState {
    val state = rememberLazyListState()
    BindScrollToTop(state)
    return state
}

/** [rememberPageListState] for a page body that is a plain `verticalScroll` column. */
@Composable
fun rememberPageScrollState(): ScrollState {
    val state = rememberScrollState()
    BindScrollToTop(state)
    return state
}

/**
 * Content padding for a page's main list: on an immersive page the first item clears the title bar
 * (the list itself runs under it), and the last card clears the floating navigation bar / the
 * gesture bar.
 */
@Composable
fun pageListPadding(top: Dp = 12.dp): PaddingValues = PaddingValues(
    top = top + LocalPageTopInset.current,
    bottom = 24.dp + LocalBottomBarSpace.current.takeIf { it > 0.dp }.let { it ?: navigationBarHeight() },
)

@Composable
private fun navigationBarHeight(): Dp = with(androidx.compose.ui.platform.LocalDensity.current) {
    androidx.compose.foundation.layout.WindowInsets.Companion.navigationBars.getBottom(this).toDp()
}

/**
 * Standard page, following the coui17 gallery structure: coui `Scaffold` on the ColorOS page color,
 * a large collapsing `TopAppBar` for top-level pages (or `SmallTopAppBar` for detail pages with a
 * back arrow), and the content below the bar. Lists inside should use [pageScroll] and
 * [pageListPadding].
 *
 * [immersive]: the body is one scrolling list (anything fixed above it, like the connection banner,
 * belongs inside it as its first item). It then starts at the top of the screen and scrolls under the
 * status bar and the frosted title bar, as ColorOS pages do; [pageListPadding] keeps its first item
 * below the bar. Pages with fixed content above the list (tabs, chips, a composer) stay non-immersive.
 */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    large: Boolean = onBack == null,
    floatingActionButton: @Composable () -> Unit = {},
    immersive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val behavior = COUIScrollBehavior()
    val color = pageContainerColor()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = color,
        topBar = {
            val nav: @Composable () -> Unit = {
                if (onBack != null) BackArrow(onBack)
            }
            if (large) {
                TopAppBar(
                    title = title,
                    subtitle = subtitle,
                    color = color,
                    scrollBehavior = behavior,
                    showDivider = false,
                    gradientGlass = true,
                    navigationIcon = nav,
                    actions = actions,
                )
            } else {
                SmallTopAppBar(
                    title = title,
                    subtitle = subtitle,
                    color = color,
                    scrollBehavior = behavior,
                    showDivider = false,
                    gradientGlass = true,
                    navigationIcon = nav,
                    actions = actions,
                )
            }
        },
        floatingActionButton = floatingActionButton,
    ) { inner ->
        val top = inner.calculateTopPadding()
        CompositionLocalProvider(
            LocalPageScrollBehavior provides behavior,
            LocalPageTopInset provides if (immersive) top else 0.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(top = if (immersive) 0.dp else top), content = content)
        }
    }
}

/** The back button of a detail page: the COUI17 glass round button of the top bar. */
@Composable
fun BackArrow(onBack: () -> Unit) {
    HighlightedIconButton(onClick = onBack, modifier = Modifier.couiTopAppBarActionItem()) {
        Icon(imageVector = COUIIcons.Back, contentDescription = stringResource(R.string.action_back))
    }
}

/** 40dp COUI17 circular toolbar icon button with standard action spacing. */
@Composable
fun ToolbarIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) {
    HighlightedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.couiTopAppBarActionItem(),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = COUITheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** A ColorOS preference group: one rounded card holding a column of rows ([bottom]: the gap under it). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, bottom: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        cornerRadius = CardDefaults.GroupCornerRadius,
        weight = CardDefaults.GroupWeight,
        modifier = modifier.padding(horizontal = 16.dp).padding(bottom = bottom),
        content = content,
    )
}
