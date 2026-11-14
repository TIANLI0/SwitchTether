package io.github.tianli0.switchtether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.ui.theme.DevType

/** Small rounded status label, e.g. LIVE / OBSERVE / BLOCKED. Text is always present (not color-only). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        Text(text = text, color = color, style = DevType.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text = text, modifier = modifier.semantics { heading() })
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InfiniteProgressIndicator(size = 28.dp)
        Spacer(Modifier.height(12.dp))
        Text(label, color = COUITheme.colorScheme.onSurfaceVariantSummary, style = COUITheme.textStyles.body2)
    }
}

/** Real error state: what happened, optional technical detail, and a retry action. */
@Composable
fun ErrorState(title: String, message: String?, retryLabel: String?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = COUITheme.textStyles.title4, color = COUITheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
        if (retryLabel != null && onRetry != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(text = retryLabel, onClick = onRetry)
        }
    }
}

@Composable
fun EmptyMessage(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
}

/** Thin banner for connection problems shown above content (not a dialog). */
@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.size(8.dp))
        Text(text, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(text = action, onClick = onAction)
    }
}

/** Card container with standard outer margins used on list pages. */
@Composable
fun PageCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // ColorOS group card (same metrics as the coui17 gallery's preference groups).
    Card(
        cornerRadius = io.github.suqi8.coui.kmp.basic.CardDefaults.GroupCornerRadius,
        weight = io.github.suqi8.coui.kmp.basic.CardDefaults.GroupWeight,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
    ) { content() }
}

/** Label/value row for detail pages; value is selectable-looking monospace when [mono]. */
@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.widthIn(min = 96.dp, max = 140.dp))
        Spacer(Modifier.size(12.dp))
        Text(value, style = if (mono) DevType.code else COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
