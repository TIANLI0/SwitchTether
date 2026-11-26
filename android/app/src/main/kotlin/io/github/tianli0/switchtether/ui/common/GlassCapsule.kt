package io.github.tianli0.switchtether.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.SpotLightDefaults
import io.github.suqi8.coui.kmp.basic.couiTouchMotion
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.glassSurface

/**
 * A 30dp capsule floating over the conversation (the run-settings chip, "stop"): frosted glass
 * like the composer under it, with COUI's touch motion — the capsule gives under the finger and
 * the press spotlight follows it, as `COUIButton` does.
 */
@Composable
fun GlassCapsule(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    val dark = COUITheme.colorScheme.background.luminance() < 0.5f
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 30.dp)
            .couiTouchMotion(
                surface = Modifier.glassSurface(shape),
                enabled = enabled,
                shape = shape,
                lightParams = SpotLightDefaults.capsuleParams2(dark),
                // The capsule takes the drag, gives under the finger and springs back, as a COUI
                // button does. Handing the drag on as well let the conversation scroll at the same
                // time, which cut the spring short: the bounce stuttered.
                consumeDrag = true,
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
