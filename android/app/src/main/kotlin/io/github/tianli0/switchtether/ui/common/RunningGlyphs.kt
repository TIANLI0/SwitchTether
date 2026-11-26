package io.github.tianli0.switchtether.ui.common

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import io.github.tianli0.switchtether.R

/*
 * "Something runs" glyphs from the useAnimations icon set (MIT, Lottie line icons on a 32 grid with
 * 2-unit strokes — the weight of the app's Phosphor Regular icons at 24dp). Single-colour strokes,
 * tinted at runtime. While [running] they loop; otherwise they rest on their first frame, which is
 * the icon at rest. Bundled in res/raw (see the licences screen).
 */

/** A background shell task: the clock's hand goes round while it runs. */
@Composable
fun BackgroundTaskGlyph(tint: Color, running: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    LottieGlyph(R.raw.lottie_clock, tint, running, modifier, size)

/** Sub-agents at work: the branch redraws and its nodes bounce while they run. */
@Composable
fun SubagentGlyph(tint: Color, running: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    LottieGlyph(R.raw.lottie_git_branch, tint, running, modifier, size)

/** Something waits for the user (a sub-agent's permission request): the bell swings. */
@Composable
fun WaitingGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) =
    LottieGlyph(R.raw.lottie_notification, tint, running = true, modifier, size)

@Composable
private fun LottieGlyph(@RawRes res: Int, tint: Color, running: Boolean, modifier: Modifier, size: Dp) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val progress by animateLottieCompositionAsState(
        composition,
        isPlaying = running,
        iterations = LottieConstants.IterateForever,
        restartOnPlay = false,
    )
    val argb = tint.toArgb()
    val dynamic = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, argb, "**"),
        rememberLottieDynamicProperty(LottieProperty.COLOR, argb, "**"),
    )
    LottieAnimation(
        composition = composition,
        progress = { if (running) progress else 0f },
        dynamicProperties = dynamic,
        modifier = modifier.size(size),
    )
}
