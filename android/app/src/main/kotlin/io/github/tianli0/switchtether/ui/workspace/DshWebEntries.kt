package io.github.tianli0.switchtether.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.home.RowIcon
import io.github.tianli0.switchtether.ui.icons.AppIcons

/** Whether the connected computer offers DeepSeek Harness Web (feature `dsh_web`, §7.14). */
@Composable
fun rememberDshWebAvailable(): Boolean {
    val container = appContainer()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val online = conn is ConnectionState.Online
    val available by produceState(false, online) {
        value = online && runCatching { "dsh_web" in (container.connection.apiOrNull()?.features() ?: emptySet()) }.getOrDefault(false)
    }
    return available
}

/** Workspace → port forwarding group: DeepSeek Harness Web under the forwards it uses. */
@Composable
fun DshWebWorkspaceRow(onOpen: () -> Unit, position: CardListPosition) {
    ArrowPreference(
        title = stringResource(R.string.dsh_web_title),
        summary = stringResource(R.string.dsh_web_entry_summary),
        startAction = { RowIcon(AppIcons.Web) },
        onClick = onOpen,
        cardListPosition = position,
    )
}

/** New session with DeepSeek Harness selected: its own web UI as the alternative. */
@Composable
fun DshWebNewSessionRow(onOpen: () -> Unit) {
    if (!rememberDshWebAvailable()) return
    GroupCard(bottom = 16.dp) {
        ArrowPreference(
            title = stringResource(R.string.dsh_web_entry_new_session),
            summary = stringResource(R.string.dsh_web_entry_new_session_summary),
            startAction = { RowIcon(AppIcons.Web) },
            onClick = onOpen,
            cardListPosition = CardListPosition.Full,
            modifier = Modifier.padding(0.dp),
        )
    }
}
