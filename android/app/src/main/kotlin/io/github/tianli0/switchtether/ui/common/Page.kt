package io.github.tianli0.switchtether.ui.common

import io.github.tianli0.switchtether.ui.icons.AppIcons

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.couiTopAppBarActionItem
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import io.github.tianli0.switchtether.R

/** Space the floating bottom navigation bar occupies over page content (set by the shell). */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

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

/** Bottom content padding for lists so the last card clears the floating navigation bar. */
@Composable
fun pageListPadding(top: Dp = 12.dp): PaddingValues = PaddingValues(top = top, bottom = 24.dp + LocalBottomBarSpace.current)

/**
 * Standard page, following the coui17 gallery structure: coui `Scaffold` on the ColorOS page color,
 * a large collapsing `TopAppBar` for top-level pages (or `SmallTopAppBar` for detail pages with a
 * back arrow), and the content below the bar. Lists inside should use [pageScroll] and
 * [pageListPadding].
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val behavior = COUIScrollBehavior()
    val color = pageContainerColor()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = color,
        topBar = {
            val nav: @Composable () -> Unit = {
                if (onBack != null) {
                    HighlightedIconButton(
                        onClick = onBack,
                        modifier = Modifier.couiTopAppBarActionItem(),
                    ) {
                        Icon(imageVector = COUIIcons.Back, contentDescription = stringResource(R.string.action_back))
                    }
                }
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
        CompositionLocalProvider(LocalPageScrollBehavior provides behavior) {
            Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()), content = content)
        }
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

/** A ColorOS preference group: one rounded card holding a column of rows. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        cornerRadius = CardDefaults.GroupCornerRadius,
        weight = CardDefaults.GroupWeight,
        modifier = modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
        content = content,
    )
}
