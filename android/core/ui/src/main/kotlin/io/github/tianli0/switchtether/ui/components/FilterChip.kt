package io.github.tianli0.switchtether.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.suqi8.coui.kmp.basic.Chip

/** Selectable filter chip: the COUI17 [Chip] (capsule, press spotlight, selected tint). */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Chip(selected = selected, onClick = onClick, label = label, modifier = modifier)
}
