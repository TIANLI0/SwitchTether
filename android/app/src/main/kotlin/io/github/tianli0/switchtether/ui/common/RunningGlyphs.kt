package io.github.tianli0.switchtether.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.tianli0.switchtether.ui.icons.AppIcons

/**
 * The background-task hourglass. While a task runs it turns over like a real one: still for most
 * of a 1.6 s beat, then a quick half turn — readable as "time is passing" without the busy feel
 * of a spinner. Still when [running] is false.
 */
@Composable
fun BackgroundTaskGlyph(tint: Color, running: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val rotation = if (running) {
        val t = rememberInfiniteTransition(label = "hourglass")
        val r by t.animateFloat(
            initialValue = 0f,
            targetValue = 180f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1600
                    0f at 0
                    0f at 1050 using FastOutSlowInEasing
                    180f at 1600
                },
            ),
            label = "hourglass-turn",
        )
        r
    } else {
        0f
    }
    Icon(AppIcons.BackgroundTask, contentDescription = null, tint = tint, modifier = modifier.size(size).graphicsLayer { rotationZ = rotation })
}

/**
 * The sub-agent tree glyph. While agents work it breathes (scale and alpha on a 1.2 s cycle), the
 * same rhythm as the running badge on session rows; still when [running] is false.
 */
@Composable
fun SubagentGlyph(tint: Color, running: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val (scale, alpha) = if (running) {
        val t = rememberInfiniteTransition(label = "subagent")
        val p by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "subagent-breath",
        )
        (0.86f + 0.14f * p) to (0.55f + 0.45f * p)
    } else {
        1f to 1f
    }
    Icon(
        AppIcons.SubAgent,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size).graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
    )
}
