package io.github.tianli0.switchtether.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.Commands
import io.github.tianli0.switchtether.remote.protocol.CreateTerminalBody
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.ShellInfo
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.protocol.TermLine
import io.github.tianli0.switchtether.remote.protocol.TerminalInfo
import io.github.tianli0.switchtether.remote.protocol.TerminalList
import io.github.tianli0.switchtether.remote.terminal.KeyEncoder
import io.github.tianli0.switchtether.remote.terminal.TermFrame
import io.github.tianli0.switchtether.remote.terminal.TermKey
import io.github.tianli0.switchtether.remote.terminal.TermScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class TermLink { IDLE, CONNECTING, OPEN, CLOSED, NEEDS_STEP_UP, NO_STEP_UP_KEY }

data class TerminalUiState(
    val shells: List<ShellInfo> = emptyList(),
    val terminals: List<TerminalInfo> = emptyList(),
    val selectedId: String? = null,
    val screen: TermScreen = TermScreen(),
    val link: TermLink = TermLink.IDLE,
    val error: String? = null,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val fontSp: Int = 12,
    val scrollback: List<TermLine> = emptyList(),
    val scrollbackTotal: Int = 0,
    val busy: Boolean = false,
    val online: Boolean = false,
)

/**
 * Terminal workspace: lists/creates terminals, keeps ONE live stream (the selected terminal) and
 * reduces snapshot/patch frames into [TermScreen]. Every create/attach is gated by step-up
 * `terminal`; an expired grant (403 / error frame / close 4401) asks again instead of failing.
 */
class TerminalViewModel(
    private val conn: ConnectionController,
    private val gate: StepUpGate,
    private val stepUpAvailable: () -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()
    private var ws: WebSocket? = null
    private var streamGen = 0
    private var resizeJob: Job? = null
    private var lastSize: Pair<Int, Int>? = null

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(online = s is ConnectionState.Online) }
                if (s is ConnectionState.Online) {
                    refresh()
                    _state.value.selectedId?.let { if (_state.value.link == TermLink.CLOSED) connect(it) }
                }
            }
        }
    }

    fun refresh() {
        val api = conn.apiOrNull() ?: return
        viewModelScope.launch {
            val r = runCatching { api.terminals() }
            r.onSuccess { list: TerminalList ->
                _state.update { st -> st.copy(shells = list.shells, terminals = list.terminals, error = null) }
            }.onFailure { e -> _state.update { it.copy(error = ConnectionManager.describe(e)) } }
        }
    }

    /** Step-up then `POST /v1/terminals` (idempotent by commandId). */
    fun create(shell: String?) {
        val api = conn.apiOrNull() ?: return
        if (!stepUpAvailable()) {
            _state.update { it.copy(link = TermLink.NO_STEP_UP_KEY) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            if (gate.ensure(StepUpScopes.TERMINAL).isFailure) {
                _state.update { it.copy(busy = false, link = TermLink.NEEDS_STEP_UP) }
                return@launch
            }
            val (cols, rows) = lastSize ?: (80 to 24)
            val out = Commands.run { id -> api.createTerminal(CreateTerminalBody(id, shell, null, cols, rows)) }
            when (out) {
                is CommandOutcome.Succeeded -> {
                    val info = runCatching { ProtocolJson.decodeFromJsonElement(TerminalInfo.serializer(), out.response.body) }.getOrNull()
                    _state.update { it.copy(busy = false, terminals = it.terminals + listOfNotNull(info)) }
                    info?.let { select(it.id) }
                }
                is CommandOutcome.Rejected -> {
                    if (out.error.code == ErrorCodes.STEP_UP_REQUIRED) gate.invalidate(StepUpScopes.TERMINAL)
                    _state.update { it.copy(busy = false, error = "${out.error.code}: ${out.error.body.message}") }
                }
                else -> _state.update { it.copy(busy = false, error = out.toString()) }
            }
        }
    }

    fun select(id: String) {
        if (_state.value.selectedId == id && _state.value.link == TermLink.OPEN) return
        _state.update { it.copy(selectedId = id, screen = TermScreen(), scrollback = emptyList(), scrollbackTotal = 0) }
        connect(id)
    }

    /** (Re)attaches: a reconnect simply receives a fresh snapshot (mosh-style). */
    fun connect(target: String? = null) {
        val id = target ?: _state.value.selectedId ?: return
        val api = conn.apiOrNull() ?: return
        ws?.cancel()
        val gen = ++streamGen
        viewModelScope.launch {
            if (!stepUpAvailable()) {
                _state.update { it.copy(link = TermLink.NO_STEP_UP_KEY) }
                return@launch
            }
            _state.update { it.copy(link = TermLink.CONNECTING, error = null) }
            if (gate.ensure(StepUpScopes.TERMINAL).isFailure) {
                _state.update { it.copy(link = TermLink.NEEDS_STEP_UP) }
                return@launch
            }
            ws = api.openTerminal(id, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen == streamGen) _state.update { it.copy(link = TermLink.OPEN) }
                    lastSize?.let { (c, r) -> webSocket.send(resizeMsg(c, r)) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != streamGen) return
                    when (val f = TermFrame.parse(text)) {
                        is TermFrame.Error -> if (f.code == ErrorCodes.STEP_UP_REQUIRED) stepUpExpired()
                        else -> _state.update { it.copy(screen = it.screen.apply(f)) }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (gen != streamGen) return
                    if (code == 4401) stepUpExpired() else _state.update { it.copy(link = TermLink.CLOSED) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != streamGen) return
                    val body = runCatching { response?.peekBody(4096)?.string() }.getOrNull()
                    if (response?.code == 403 && body?.contains(ErrorCodes.STEP_UP_REQUIRED) == true) {
                        stepUpExpired()
                    } else {
                        _state.update { it.copy(link = TermLink.CLOSED, error = response?.let { r -> "HTTP ${r.code} ${body.orEmpty().take(160)}" } ?: ConnectionManager.describe(t)) }
                    }
                }
            })
        }
    }

    private fun stepUpExpired() {
        gate.invalidate(StepUpScopes.TERMINAL)
        ws?.cancel()
        _state.update { it.copy(link = TermLink.NEEDS_STEP_UP) }
    }

    fun disconnect() {
        streamGen++
        ws?.close(1000, null)
        ws = null
        _state.update { it.copy(link = TermLink.IDLE) }
    }

    fun kill() {
        val id = _state.value.selectedId ?: return
        val api = conn.apiOrNull() ?: return
        viewModelScope.launch {
            runCatching { api.killTerminal(id) }.onFailure { e -> _state.update { it.copy(error = ConnectionManager.describe(e)) } }
            disconnect()
            _state.update { st -> st.copy(selectedId = null, screen = TermScreen(), terminals = st.terminals.filter { it.id != id }) }
            refresh()
        }
    }

    // ---- input -------------------------------------------------------------------------------------

    fun typeText(s: String) {
        val st = _state.value
        send(KeyEncoder.text(s, st.ctrl, st.alt))
        clearLatches()
    }

    fun key(k: TermKey) {
        val st = _state.value
        send(KeyEncoder.key(k, st.screen.flags.applicationCursor, st.ctrl, st.alt))
        clearLatches()
    }

    fun paste(s: String) = send(KeyEncoder.paste(s, _state.value.screen.flags.bracketedPaste))

    fun toggleCtrl() = _state.update { it.copy(ctrl = !it.ctrl) }

    fun toggleAlt() = _state.update { it.copy(alt = !it.alt) }

    private fun clearLatches() = _state.update { if (it.ctrl || it.alt) it.copy(ctrl = false, alt = false) else it }

    private fun send(data: String) {
        if (data.isEmpty() || _state.value.link != TermLink.OPEN) return
        ws?.send(buildJsonObject { put("type", "input"); put("data", data) }.toString())
    }

    /** Debounced resize from the measured cell grid. */
    fun resize(cols: Int, rows: Int) {
        if (cols < 10 || rows < 4 || lastSize == (cols to rows)) return
        lastSize = cols to rows
        resizeJob?.cancel()
        resizeJob = viewModelScope.launch {
            delay(250)
            ws?.send(resizeMsg(cols, rows))
        }
    }

    private fun resizeMsg(c: Int, r: Int) = buildJsonObject { put("type", "resize"); put("cols", c); put("rows", r) }.toString()

    fun fontSize(delta: Int) = _state.update { it.copy(fontSp = (it.fontSp + delta).coerceIn(8, 22)) }

    /** Loads up to [count] more scrollback lines above the screen (`GET …/scrollback`). */
    fun loadScrollback(count: Int = 200) {
        val id = _state.value.selectedId ?: return
        val api = conn.apiOrNull() ?: return
        viewModelScope.launch {
            try {
                val total = api.terminalScrollback(id, 0, 1).total
                val have = _state.value.scrollback.size
                val end = (total - have).coerceAtLeast(0)
                val start = (end - count).coerceAtLeast(0)
                if (end <= 0) {
                    _state.update { it.copy(scrollbackTotal = total) }
                    return@launch
                }
                val page = api.terminalScrollback(id, start, end - start)
                _state.update { it.copy(scrollback = page.lines + it.scrollback, scrollbackTotal = total) }
            } catch (e: ApiException) {
                _state.update { it.copy(error = "${e.code}: ${e.body.message}") }
            } catch (e: Exception) {
                _state.update { it.copy(error = ConnectionManager.describe(e)) }
            }
        }
    }

    override fun onCleared() {
        streamGen++
        ws?.cancel()
    }
}
