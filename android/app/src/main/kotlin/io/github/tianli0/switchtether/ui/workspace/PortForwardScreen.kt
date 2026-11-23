package io.github.tianli0.switchtether.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.forward.ActiveForward
import io.github.tianli0.switchtether.forward.PortForwards
import io.github.tianli0.switchtether.forward.StartResult
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.ListeningPort
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.loadable
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.switchtether.ui.components.StatusPill
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.home.RowIcon
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PortForwardUiState(
    val ports: Loadable<List<ListeningPort>> = Loadable.Loading,
    val resumable: List<ForwardView> = emptyList(),
    /** Remote port being started (biometric prompt / lease request in flight). */
    val busyPort: Int? = null,
    val online: Boolean = false,
)

/** One-shot outcomes for toasts/dialogs. */
sealed interface ForwardMessage {
    data class Started(val forward: ActiveForward, val listening: Boolean?) : ForwardMessage
    data class Extended(val untilMs: Long) : ForwardMessage
    data class LocalPortBusy(val localPort: Int, val remotePort: Int) : ForwardMessage
    data class Failed(val message: String) : ForwardMessage
    data object NoStepUpKey : ForwardMessage
    data class Ended(val remotePort: Int) : ForwardMessage
}

class PortForwardViewModel(private val conn: ConnectionController, private val forwards: PortForwards) : ViewModel() {
    private val _state = MutableStateFlow(PortForwardUiState())
    val state: StateFlow<PortForwardUiState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<ForwardMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<ForwardMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(online = s is ConnectionState.Online) }
                if (s is ConnectionState.Online) refresh()
            }
        }
        viewModelScope.launch { forwards.ended.collect { _messages.tryEmit(ForwardMessage.Ended(it)) } }
    }

    fun refresh() {
        val api = conn.apiOrNull() ?: return
        viewModelScope.launch {
            val ports = loadable { api.forwardPorts().ports }
            val resumable = forwards.resumable()
            _state.update { it.copy(ports = ports, resumable = resumable) }
        }
    }

    fun start(remotePort: Int, localPort: Int = remotePort, process: String? = null) {
        if (_state.value.busyPort != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyPort = remotePort) }
            report(forwards.start(remotePort, localPort, process))
            _state.update { it.copy(busyPort = null) }
            refresh()
        }
    }

    fun resume(lease: ForwardView) {
        viewModelScope.launch {
            report(forwards.attach(lease))
            refresh()
        }
    }

    fun extend(f: ActiveForward) {
        viewModelScope.launch {
            when (val r = forwards.extend(f.leaseId)) {
                is StartResult.Started -> _messages.tryEmit(ForwardMessage.Extended(r.forward.expiresAtMs))
                else -> report(r)
            }
        }
    }

    fun stop(f: ActiveForward) {
        forwards.stop(f.leaseId)
        refresh()
    }

    private fun report(r: StartResult) {
        _messages.tryEmit(
            when (r) {
                is StartResult.Started -> ForwardMessage.Started(r.forward, r.listening)
                is StartResult.LocalPortBusy -> ForwardMessage.LocalPortBusy(r.localPort, r.remotePort)
                is StartResult.Failed -> ForwardMessage.Failed(r.message)
                StartResult.NoStepUpKey -> ForwardMessage.NoStepUpKey
                StartResult.NotConfirmed -> return
            },
        )
    }
}

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, context.getString(R.string.forward_no_browser), Toast.LENGTH_SHORT).show() }
}

private fun copy(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, context.getString(R.string.forward_copied), Toast.LENGTH_SHORT).show()
}

private fun timeOf(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * Port forwarding (docs/ANDROID_UI.md §16): the computer's listening ports one tap away from the
 * phone's browser at the same `localhost:<port>` address.
 */
@Composable
fun PortForwardScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm = containerViewModel { c -> PortForwardViewModel(c.connection, c.portForwards) }
    val st by vm.state.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val active by container.portForwards.active.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf<Pair<String, String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refresh()
        vm.messages.collect { m ->
            when (m) {
                is ForwardMessage.Started -> {
                    if (m.listening == false) notice = context.getString(R.string.forward_not_listening, m.forward.remotePort)
                    else openInBrowser(context, m.forward.url)
                }
                is ForwardMessage.Extended ->
                    Toast.makeText(context, context.getString(R.string.forward_extended, timeOf(m.untilMs)), Toast.LENGTH_SHORT).show()
                is ForwardMessage.LocalPortBusy -> {
                    notice = context.getString(R.string.forward_local_busy, m.localPort)
                    manual = m.remotePort.toString() to ""
                }
                is ForwardMessage.Failed -> notice = context.getString(R.string.forward_failed, m.message)
                ForwardMessage.NoStepUpKey -> notice = context.getString(R.string.forward_no_stepup)
                is ForwardMessage.Ended ->
                    Toast.makeText(context, context.getString(R.string.forward_ended, m.remotePort), Toast.LENGTH_SHORT).show()
            }
        }
    }

    notice?.let { text ->
        io.github.tianli0.switchtether.ui.common.ConfirmDialog(
            show = true,
            title = stringResource(R.string.forward_title),
            message = text,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { notice = null },
            onDismiss = { notice = null },
        )
    }
    manual?.let { (remoteDraft0, localDraft0) ->
        ManualPortDialog(
            initialRemote = remoteDraft0,
            initialLocal = localDraft0,
            onDismiss = { manual = null },
            onConfirm = { remote, local ->
                manual = null
                vm.start(remote, local)
            },
        )
    }

    Page(
        title = stringResource(R.string.forward_title),
        onBack = onBack,
        large = false,
        actions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), vm::refresh) },
        immersive = true,
    ) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { ConnectionBanner(conn, { container.connection.retryNow() }) }
            if (active.isNotEmpty()) {
                item { SmallTitle(stringResource(R.string.forward_active)) }
                item {
                    GroupCard {
                        active.forEachIndexed { i, f ->
                            ActiveForwardRow(
                                f,
                                cardListPositionOf(i, active.size),
                                onOpen = { openInBrowser(context, f.url) },
                                onCopy = { copy(context, f.url) },
                                onExtend = { vm.extend(f) },
                                onStop = { vm.stop(f) },
                            )
                        }
                    }
                }
            }
            val resumable = st.resumable.filter { r -> active.none { it.leaseId == r.id } }
            if (resumable.isNotEmpty()) {
                item { SmallTitle(stringResource(R.string.forward_resumable)) }
                item {
                    GroupCard {
                        resumable.forEachIndexed { i, r ->
                            BasicComponent(
                                title = "localhost:${r.port}",
                                summary = stringResource(R.string.forward_resume_summary) + " · " + stringResource(R.string.forward_until, timeOf(r.expiresAtMs)),
                                startAction = { RowIcon(AppIcons.PortForward) },
                                onClick = { vm.resume(r) },
                                cardListPosition = cardListPositionOf(i, resumable.size),
                            )
                        }
                    }
                }
            }
            item { SmallTitle(stringResource(R.string.forward_listening)) }
            item {
                val ports = st.ports.valueOrNull.orEmpty()
                // Rows: [state line when there is no port] + ports + "enter a port".
                val lead = if (ports.isEmpty()) 1 else 0
                val rows = lead + ports.size + 1
                GroupCard {
                    when (val p = st.ports) {
                        is Loadable.Failed -> BasicComponent(
                            title = p.error.title(),
                            summary = p.error.detail,
                            cardListPosition = cardListPositionOf(0, rows),
                        )
                        Loadable.Loading -> BasicComponent(
                            title = stringResource(R.string.forward_listening) + "…",
                            cardListPosition = cardListPositionOf(0, rows),
                        )
                        is Loadable.Ready -> if (ports.isEmpty()) {
                            BasicComponent(
                                summary = stringResource(R.string.forward_listening_empty),
                                cardListPosition = cardListPositionOf(0, rows),
                            )
                        }
                    }
                    ports.forEachIndexed { i, port ->
                        val forwarded = active.any { it.remotePort == port.port }
                        BasicComponent(
                            title = ":${port.port}",
                            summary = port.process,
                            startAction = { RowIcon(AppIcons.Web) },
                            endActions = when {
                                forwarded -> ({ StatusPill(stringResource(R.string.forward_already), LocalDevColors.current.ok) })
                                st.busyPort == port.port -> ({ StatusPill(stringResource(R.string.forward_status_connecting), LocalDevColors.current.info) })
                                else -> null
                            },
                            enabled = st.online && st.busyPort == null,
                            onClick = {
                                val existing = active.firstOrNull { it.remotePort == port.port }
                                if (existing != null) openInBrowser(context, existing.url) else vm.start(port.port, port.port, port.process)
                            },
                            cardListPosition = cardListPositionOf(lead + i, rows),
                        )
                    }
                    BasicComponent(
                        title = stringResource(R.string.forward_manual),
                        startAction = { RowIcon(AppIcons.Add) },
                        enabled = st.online && st.busyPort == null,
                        onClick = { manual = "" to "" },
                        cardListPosition = cardListPositionOf(rows - 1, rows),
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.forward_hint),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ActiveForwardRow(
    f: ActiveForward,
    position: CardListPosition,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onExtend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = LocalDevColors.current
    val (statusText, statusColor) = when (f.status) {
        TunnelStatus.OPEN -> stringResource(R.string.forward_status_open) to colors.ok
        TunnelStatus.CONNECTING -> stringResource(R.string.forward_status_connecting) to colors.info
        TunnelStatus.WAITING, TunnelStatus.ENDED -> stringResource(R.string.forward_status_waiting) to colors.warn
    }
    val summary = listOfNotNull(
        stringResource(R.string.forward_to_computer, f.remotePort) + (f.process?.let { " · $it" } ?: ""),
        listOfNotNull(
            pluralStringResource(R.plurals.forward_connections, f.connections, f.connections),
            "↓ ${Formatters.bytes(f.bytesDown)}",
            stringResource(R.string.forward_via_relay).takeIf { f.viaRelay },
        ).joinToString(" · "),
        stringResource(R.string.forward_until, timeOf(f.expiresAtMs)),
    ).joinToString("\n")
    BasicComponent(
        title = "localhost:${f.localPort}",
        summary = summary,
        startAction = { RowIcon(AppIcons.PortForward) },
        endActions = { StatusPill(statusText, statusColor) },
        bottomAction = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) { TextButton(text = stringResource(R.string.forward_open), onClick = onOpen, modifier = Modifier.fillMaxWidth()) }
                Box(Modifier.weight(1f)) { TextButton(text = stringResource(R.string.forward_copy), onClick = onCopy, modifier = Modifier.fillMaxWidth()) }
                Box(Modifier.weight(1f)) { TextButton(text = stringResource(R.string.forward_extend), onClick = onExtend, modifier = Modifier.fillMaxWidth()) }
                Box(Modifier.weight(1f)) { TextButton(text = stringResource(R.string.forward_stop), onClick = onStop, modifier = Modifier.fillMaxWidth()) }
            }
        },
        onClick = onOpen,
        cardListPosition = position,
    )
}

@Composable
private fun ManualPortDialog(
    initialRemote: String,
    initialLocal: String,
    onDismiss: () -> Unit,
    onConfirm: (remote: Int, local: Int) -> Unit,
) {
    var remote by remember { mutableStateOf(initialRemote) }
    var local by remember { mutableStateOf(initialLocal) }
    val remotePort = remote.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val localPort = if (local.isBlank()) remotePort else local.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    WindowDialog(show = true, title = stringResource(R.string.forward_manual), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(remote, { remote = it.filter(Char::isDigit).take(5) }, stringResource(R.string.forward_remote_port), keyboardOptions = numbers)
                TextInput(local, { local = it.filter(Char::isDigit).take(5) }, stringResource(R.string.forward_local_port), keyboardOptions = numbers)
                Text(
                    if ((remote.isNotBlank() && remotePort == null) || (local.isNotBlank() && localPort == null)) stringResource(R.string.forward_invalid_port)
                    else stringResource(R.string.forward_local_hint),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            DialogButtonBar(
                negative = DialogButtonBarAction(text = stringResource(R.string.action_cancel), onClick = onDismiss),
                positive = DialogButtonBarAction(
                    text = stringResource(R.string.forward_start),
                    enabled = remotePort != null && localPort != null,
                    onClick = { if (remotePort != null && localPort != null) onConfirm(remotePort, localPort) },
                ),
            )
        }
    }
}
