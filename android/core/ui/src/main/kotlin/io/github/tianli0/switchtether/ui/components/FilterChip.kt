package io.github.tianli0.switchtether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/** Selectable filter chip with a 40dp touch target and proper selected semantics. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val c = COUITheme.colorScheme
    Box(
        modifier
            .heightIn(min = 40.dp)
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (selected) c.primary.copy(alpha = 0.14f) else c.surfaceContainer)
            .border(1.dp, if (selected) c.primary else c.dividerLine, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = COUITheme.textStyles.footnote1, color = if (selected) c.primary else c.onSurface, maxLines = 1)
    }
}
