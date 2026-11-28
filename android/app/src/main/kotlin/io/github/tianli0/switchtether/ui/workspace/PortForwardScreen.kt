package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.SmallButton
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
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.forward.ActiveForward
import io.github.tianli0.switchtether.forward.StartResult
import io.github.tianli0.switchtether.remote.forward.TunnelStatus
import io.github.tianli0.switchtether.remote.protocol.ForwardView
import io.github.tianli0.switchtether.remote.protocol.ListeningPort
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.switchtether.ui.common.loadable
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.switchtether.ui.common.rememberPageListState
import io.github.tianli0.switchtether.ui.common.title
import io.github.tianli0.coui17.patterns.StatusPill
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
    /** Lease being resumed or extended (one request at a time per lease). */
    val busyLease: String? = null,
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

class PortForwardViewModel(private val conn: ConnectionController, private val forwards: io.github.tianli0.switchtether.forward.Forwards) : ViewModel() {
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
        if (_state.value.busyLease != null) return
        _state.update { it.copy(busyLease = lease.id) }
        viewModelScope.launch {
            try {
                report(forwards.attach(lease))
            } finally {
                _state.update { it.copy(busyLease = null) }
            }
            refresh()
        }
    }

    fun extend(f: ActiveForward) {
        if (_state.value.busyLease != null) return
        _state.update { it.copy(busyLease = f.leaseId) }
        viewModelScope.launch {
            try {
                when (val r = forwards.extend(f.leaseId)) {
                    is StartResult.Started -> _messages.tryEmit(ForwardMessage.Extended(r.forward.expiresAtMs))
                    else -> report(r)
                }
            } finally {
                _state.update { it.copy(busyLease = null) }
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
    var manual by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = PortPairSaver) { mutableStateOf<Pair<String, String>?>(null) }
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
        io.github.tianli0.coui17.patterns.ConfirmDialog(
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
        actions = { io.github.tianli0.switchtether.ui.common.RefreshToolbarIcon(vm::refresh) },
        immersive = true,
    ) {
        LazyColumn(state = rememberPageListState(), flingBehavior = io.github.tianli0.coui17.patterns.pageFlingBehavior(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            // Sections are keyed items: a forward that starts or stops fades its section in or
            // out and the sections below glide to their place.
            item(key = "connection") { ConnectionBanner(conn, { container.connection.retryNow() }) }
            if (active.isNotEmpty()) {
                item(key = "active-h") { SmallTitle(stringResource(R.string.forward_active), Modifier.animateItem()) }
                item(key = "active") {
                    GroupCard(Modifier.animateItem()) {
                        active.forEachIndexed { i, f ->
                            ActiveForwardRow(
                                f,
                                cardListPositionOf(i, active.size),
                                onOpen = { openInBrowser(context, f.url) },
                                onCopy = { copy(context, f.url) },
                                onExtend = { vm.extend(f) },
                                extending = st.busyLease == f.leaseId,
                                onStop = { vm.stop(f) },
                            )
                        }
                    }
                }
            }
            val resumable = st.resumable.filter { r -> active.none { it.leaseId == r.id } }
            if (resumable.isNotEmpty()) {
                item(key = "resumable-h") { SmallTitle(stringResource(R.string.forward_resumable), Modifier.animateItem()) }
                item(key = "resumable") {
                    GroupCard(Modifier.animateItem()) {
                        resumable.forEachIndexed { i, r ->
                            BasicComponent(
                                title = "localhost:${r.port}",
                                summary = stringResource(R.string.forward_resume_summary) + " · " + stringResource(R.string.forward_until, timeOf(r.expiresAtMs)),
                                startAction = { RowIcon(AppIcons.PortForward) },
                                endActions = if (st.busyLease == r.id) ({ StatusPill(stringResource(R.string.forward_status_connecting), LocalDevColors.current.info) }) else null,
                                onClick = { vm.resume(r) },
                                enabled = st.online && st.busyLease == null,
                                cardListPosition = cardListPositionOf(i, resumable.size),
                            )
                        }
                    }
                }
            }
            item(key = "listening-h") { SmallTitle(stringResource(R.string.forward_listening), Modifier.animateItem()) }
            item(key = "listening") {
                // A PC listens on dozens of ports (chat apps, game launchers, system services): the
                // dev servers come first in port order, the rest fold behind one row. "Enter a
                // port" leads the card instead of trailing ~30 rows.
                var showOthers by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                val all = st.ports.valueOrNull.orEmpty().sortedBy { it.port }
                val (dev, others) = all.partition { isLikelyDevServer(it.process) }
                val ports = if (showOthers || dev.isEmpty()) all.sortedWith(compareBy({ !isLikelyDevServer(it.process) }, { it.port })) else dev
                val folded = if (showOthers || dev.isEmpty()) 0 else others.size
                // Rows: "enter a port" + [state line when there is no port] + ports + [show others].
                val lead = 1 + if (all.isEmpty()) 1 else 0
                val rows = lead + ports.size + (if (folded > 0 || (showOthers && dev.isNotEmpty() && others.isNotEmpty())) 1 else 0)
                GroupCard(Modifier.animateItem()) {
                    BasicComponent(
                        title = stringResource(R.string.forward_manual),
                        startAction = { RowIcon(AppIcons.Add) },
                        enabled = st.online && st.busyPort == null,
                        onClick = { manual = "" to "" },
                        cardListPosition = cardListPositionOf(0, rows),
                    )
                    when (val p = st.ports) {
                        is Loadable.Failed -> BasicComponent(
                            title = p.error.title(),
                            summary = p.error.detail,
                            cardListPosition = cardListPositionOf(1, rows),
                        )
                        Loadable.Loading -> BasicComponent(
                            title = stringResource(R.string.forward_listening) + "…",
                            cardListPosition = cardListPositionOf(1, rows),
                        )
                        is Loadable.Ready -> if (all.isEmpty()) {
                            BasicComponent(
                                summary = stringResource(R.string.forward_listening_empty),
                                cardListPosition = cardListPositionOf(1, rows),
                            )
                        }
                    }
                    ports.forEachIndexed { i, port ->
                        val forwarded = active.any { it.remotePort == port.port }
                        BasicComponent(
                            title = ":${port.port}",
                            // Every row two lines high, named or not.
                            summary = port.process?.takeIf { it.isNotBlank() } ?: stringResource(R.string.forward_unknown_process),
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
                    if (folded > 0 || (showOthers && dev.isNotEmpty() && others.isNotEmpty())) {
                        BasicComponent(
                            title = if (showOthers) stringResource(R.string.forward_hide_other_ports) else stringResource(R.string.forward_show_other_ports, others.size),
                            onClick = { showOthers = !showOthers },
                            cardListPosition = cardListPositionOf(rows - 1, rows),
                        )
                    }
                }
            }
            item(key = "hint") {
                Text(
                    stringResource(R.string.forward_hint),
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.animateItem().padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp),
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
    extending: Boolean = false,
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
            // The row's own actions: compact capsules that wrap, not four slabs across the card.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallButton(stringResource(R.string.forward_open), onOpen, tone = ActionTone.Primary)
                SmallButton(stringResource(R.string.forward_copy), onCopy)
                SmallButton(stringResource(R.string.forward_extend), onExtend, enabled = !extending)
                SmallButton(stringResource(R.string.forward_stop), onStop, tone = ActionTone.Danger)
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
    var remote by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialRemote) }
    var local by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialLocal) }
    val remotePort = remote.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val localPort = if (local.isBlank()) remotePort else local.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    FormDialog(
        show = true,
        title = stringResource(R.string.forward_manual),
        confirmText = stringResource(R.string.forward_start),
        confirmEnabled = remotePort != null && localPort != null,
        onConfirm = { if (remotePort != null && localPort != null) onConfirm(remotePort, localPort) },
        onDismiss = onDismiss,
    ) {
        TextInput(remote, { remote = it.filter(Char::isDigit).take(5) }, stringResource(R.string.forward_remote_port), keyboardOptions = numbers)
        TextInput(local, { local = it.filter(Char::isDigit).take(5) }, stringResource(R.string.forward_local_port), keyboardOptions = numbers)
        Text(
            if ((remote.isNotBlank() && remotePort == null) || (local.isNotBlank() && localPort == null)) stringResource(R.string.forward_invalid_port)
            else stringResource(R.string.forward_local_hint),
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )

    }
}

/** The manual port dialog's drafts (remote, local) across rotation. */
private val PortPairSaver = androidx.compose.runtime.saveable.Saver<Pair<String, String>?, List<String>>(
    save = { p -> p?.let { listOf(it.first, it.second) } },
    restore = { l -> if (l.size == 2) l[0] to l[1] else null },
)

/** Runtimes and servers people forward from a phone: their listening ports are shown first. */
private val DEV_PROCESSES = listOf(
    "node", "bun", "deno", "python", "py", "uvicorn", "gunicorn", "java", "javaw", "ruby", "rails", "php", "go",
    "dotnet", "cargo", "vite", "next", "esbuild", "webpack", "hugo", "jekyll", "nginx", "caddy", "httpd", "apache",
    "docker", "com.docker", "wslrelay", "code", "jupyter", "postgres", "mysqld", "redis", "mongod", "dsh",
)

internal fun isLikelyDevServer(process: String?): Boolean {
    val p = process?.lowercase()?.removeSuffix(".exe")?.substringAfterLast('/')?.substringAfterLast('\\') ?: return false
    return DEV_PROCESSES.any { p == it || p.startsWith("$it-") || p.startsWith("${it}3") || p.startsWith("$it.") }
}
