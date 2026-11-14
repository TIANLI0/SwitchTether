package io.github.tianli0.switchtether.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.components.EmptyMessage

/** Phase B: terminal renderer, files and git. */
@Composable
fun WorkspaceScreen() {
    Page(title = stringResource(R.string.tab_workspace)) {
        Column(Modifier.padding(top = 12.dp)) {
            io.github.tianli0.switchtether.ui.common.GroupCard {
                io.github.suqi8.coui.kmp.basic.EmptyState(
                    title = stringResource(R.string.workspace_coming),
                    subtitle = stringResource(R.string.workspace_coming_detail),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            Text(stringResource(R.string.workspace_security), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}
