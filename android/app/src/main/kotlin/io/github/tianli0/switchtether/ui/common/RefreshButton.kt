package io.github.tianli0.switchtether.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.HighlightedIconButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.couiTopAppBarActionItem
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.icons.AppIcons
import kotlinx.coroutines.launch

/**
 * The title-bar refresh key. Every tap turns the arrow one full circle, so a refresh that finishes
 * at once still shows that it happened; while [refreshing] stays true it keeps turning, a whole
 * turn at a time, and stops upright.
 */
@Composable
fun RefreshToolbarIcon(
    onClick: () -> Unit,
    refreshing: Boolean = false,
    contentDescription: String = stringResource(R.string.action_refresh),
    enabled: Boolean = true,
) {
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val stillRefreshing by rememberUpdatedState(refreshing)
    suspend fun spin() {
        do {
            angle.animateTo(angle.value + 360f, tween(TURN_MS, easing = io.github.suqi8.coui.kmp.anim.COUIEase))
            angle.snapTo(angle.value % 360f)
        } while (stillRefreshing)
    }
    // A refresh started elsewhere (pull to refresh, first load) turns the key too.
    LaunchedEffect(refreshing) { if (refreshing && !angle.isRunning) spin() }
    HighlightedIconButton(
        onClick = {
            onClick()
            if (!angle.isRunning) scope.launch { spin() }
        },
        enabled = enabled,
        modifier = Modifier.couiTopAppBarActionItem(),
    ) {
        Icon(
            AppIcons.Refresh,
            contentDescription = contentDescription,
            tint = COUITheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = angle.value },
        )
    }
}

private const val TURN_MS = 600
