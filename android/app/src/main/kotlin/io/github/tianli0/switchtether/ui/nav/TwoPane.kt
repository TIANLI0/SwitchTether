package io.github.tianli0.switchtether.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.icons.AppIcons

/**
 * Tablets and unfolded foldables (docs/ANDROID_UI.md §26): from this window width on, a tab's root
 * list stays on the left and what it opens shows on the right. Narrower windows (every phone, a
 * folded foldable, split screen) keep the one-page navigation unchanged.
 */
val TwoPaneMinWidth: Dp = 840.dp

fun usesTwoPanes(windowWidth: Dp): Boolean = windowWidth >= TwoPaneMinWidth

/** Width of the list pane for a content area [width] wide: 40 %, between 320 and 420dp. */
fun listPaneWidth(width: Dp): Dp = (width * 0.4f).coerceIn(320.dp, 420.dp)

/** The session open in the detail pane, so the list beside it can mark its row (null on phones). */
val LocalSelectedSessionId = compositionLocalOf<String?> { null }

/** List on the left (fixed width), a hairline, detail on the right. */
@Composable
fun ListDetailPanes(list: @Composable () -> Unit, detail: @Composable () -> Unit, modifier: Modifier = Modifier, listWidth: Dp? = null) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = listWidth ?: listPaneWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(w).fillMaxHeight().testTag("pane-list")) { list() }
            Box(Modifier.width(0.5.dp).fillMaxHeight().background(COUITheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            Box(Modifier.weight(1f).fillMaxHeight().testTag("pane-detail")) { detail() }
        }
    }
}

/** The detail pane before anything is chosen. */
@Composable
fun PaneEmptyState(tab: Tab) {
    val (icon, text) = when (tab) {
        Tab.HOME, Tab.SESSIONS -> AppIcons.Sessions to R.string.pane_empty_sessions
        Tab.WORKSPACE -> AppIcons.Workspace to R.string.pane_empty_workspace
        Tab.MANAGE -> AppIcons.Manage to R.string.pane_empty_manage
    }
    Box(Modifier.fillMaxSize().testTag("pane-empty"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
            Icon(icon, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
            Text(stringResource(text), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
}
