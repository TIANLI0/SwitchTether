package io.github.tianli0.switchtether.forward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.protocol.DshWebOpen
import io.github.tianli0.switchtether.remote.protocol.DshWebStates
import io.github.tianli0.switchtether.remote.protocol.DshWebStatus
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Addresses of DeepSeek Harness Web on the phone (docs/PROTOCOL.md §7.14). The page is served from
 * `http://localhost:<local port>/` by the forward; dsh binds its sign-in cookie to that authority
 * (30 days, signed with a secret kept in the computer's `DSH_HOME`), so the phone keeps using one
 * fixed local port to stay signed in across restarts of `dsh web`.
 */
object DshWebUrls {
    /** Phone ports tried in order (dsh's own default first). */
    val LOCAL_PORTS: List<Int> = (3080..3089).toList()

    fun origin(localPort: Int): String = "http://localhost:$localPort"

    /** The first page: dsh's sign-in link when SwitchTether started it, else the root. */
    fun startUrl(localPort: Int, launchPath: String?): String =
        origin(localPort) + (launchPath?.takeIf { it.startsWith("/?token=") && tokenOf(it) != null } ?: "/")

    /** Navigation inside the WebView is limited to the forwarded origin (scheme, host and port). */
    fun isSameOrigin(url: String?, localPort: Int): Boolean {
        val u = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
        return u.scheme.equals("http", ignoreCase = true) &&
            (u.host.equals("localhost", ignoreCase = true)) &&
            u.port == localPort
    }

    /** Links that leave the forwarded origin and may go to the phone's browser. */
    fun isExternalWeb(url: String?): Boolean {
        val scheme = runCatching { java.net.URI(url ?: return false).scheme }.getOrNull() ?: return false
        return scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)
    }

    /**
     * The launch token from what the user pasted: the whole line dsh prints
     * (`dsh web: http://127.0.0.1:3080/?token=…`), the URL, or the token itself.
     */
    fun tokenOf(pasted: String): String? {
        val t = pasted.trim()
        val raw = Regex("""[?&]token=([A-Za-z0-9_-]+)""").find(t)?.groupValues?.get(1)
            ?: t.takeIf { it.matches(Regex("""[A-Za-z0-9_-]{16,}""")) }
        return raw?.takeIf { it.length in 16..256 }
    }

    /**
     * A CSS colour from `getComputedStyle` / `<meta name="theme-color">` (`rgb(…)`, `rgba(…)`,
     * `#rgb`, `#rrggbb`) as opaque ARGB, or null (transparent, unknown).
     */
    fun parseCssColor(css: String?): Int? {
        val s = css?.trim()?.trim('"')?.lowercase() ?: return null
        Regex("""rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)(?:[\s,/]+([\d.]+%?))?\s*\)""").matchEntire(s)?.let { m ->
            val (r, g, b) = m.destructured
            val a = m.groupValues[4]
            if (a.isNotEmpty()) {
                val alpha = if (a.endsWith("%")) a.dropLast(1).toFloatOrNull()?.div(100f) else a.toFloatOrNull()
                if (alpha == null || alpha < 0.5f) return null
            }
            return rgb(r.toInt(), g.toInt(), b.toInt())
        }
        if (s.startsWith("#")) {
            val h = s.drop(1)
            val full = when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("")
                6 -> h
                8 -> h.take(6)
                else -> return null
            }
            val v = full.toIntOrNull(16) ?: return null
            return (0xFF shl 24) or v
        }
        return null
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Whether dark status-bar icons read on [argb] (relative luminance above the midpoint). */
    fun wantsDarkIcons(argb: Int): Boolean {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
        return l > 0.4
    }

    /** JS returning the colour the page shows at its top (theme-color, else the first painted background). */
    const val PAGE_COLOR_JS = """(function(){try{var m=document.querySelector('meta[name="theme-color"]');if(m&&m.content)return m.content;var e=document.elementFromPoint(innerWidth/2,1);while(e){var b=getComputedStyle(e).backgroundColor;if(b&&b!=='transparent'&&b.indexOf('rgba(0, 0, 0, 0)')<0)return b;e=e.parentElement}return getComputedStyle(document.body||document.documentElement).backgroundColor||''}catch(x){return ''}})()"""
}

/** What the DeepSeek Harness Web page shows. */
sealed interface DshWebUi {
    data object Checking : DshWebUi
    data object Offline : DshWebUi
    data object NotInstalled : DshWebUi
    /** Not running (or the user declined the confirmation); [error] = why the last start failed. */
    data class Idle(val status: DshWebStatus?, val error: String? = null) : DshWebUi
    data object Confirming : DshWebUi
    data class Starting(val sinceMs: Long) : DshWebUi
    data class Ready(
        val startUrl: String,
        val localPort: Int,
        val remotePort: Int,
        val leaseId: String,
        /** SwitchTether started this `dsh web` (it can be stopped from the phone). */
        val owned: Boolean,
        /** Changes each time a fresh start URL is issued, so the WebView loads it once. */
        val generation: Int,
    ) : DshWebUi
    data object NoStepUpKey : DshWebUi
    data class Failed(val message: String) : DshWebUi
}

/**
 * Opens DeepSeek Harness Web: biometric step-up `port_forward` → `POST /v1/dsh-web/open` (reuses a
 * running `dsh web` or starts one; polls while it starts) → the lease is served on the phone's
 * loopback by [PortForwards] like any forward → the WebView loads it.
 */
class DshWebViewModel(
    private val conn: ConnectionController,
    private val gate: StepUpGate,
    private val stepUpAvailable: () -> Boolean,
    private val forwards: Forwards,
) : ViewModel() {
    private val _ui = MutableStateFlow<DshWebUi>(DshWebUi.Checking)
    val ui: StateFlow<DshWebUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var autoOpened = false

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it is ConnectionState.Online }.collect { s ->
                val online = s is ConnectionState.Online
                when {
                    // A page that is open stays: the forward reconnects by itself and serves a
                    // "computer unreachable" page meanwhile.
                    _ui.value is DshWebUi.Ready -> Unit
                    !online -> if (job?.isActive != true) _ui.value = DshWebUi.Offline
                    else -> if (job?.isActive != true) check()
                }
            }
        }
        viewModelScope.launch {
            // The computer ended the forward (stopped there, expired, revoked): back to the status.
            forwards.ended.collect { port ->
                val r = _ui.value as? DshWebUi.Ready ?: return@collect
                if (r.remotePort == port) check()
            }
        }
    }

    /** Current state on the computer; opens straight away the first time (the user came here to open it). */
    fun check() {
        job?.cancel()
        job = viewModelScope.launch {
            val api = conn.apiOrNull() ?: run { _ui.value = DshWebUi.Offline; return@launch }
            _ui.value = DshWebUi.Checking
            val st = try {
                api.dshWebStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = DshWebUi.Failed(message(e))
                return@launch
            }
            if (st.state == DshWebStates.NOT_INSTALLED) {
                _ui.value = DshWebUi.NotInstalled
                return@launch
            }
            // Already forwarded on this phone: no confirmation needed (the lease is the authorisation).
            val existing = st.port?.takeIf { st.running }?.let { p -> forwards.active.value.firstOrNull { it.remotePort == p } }
            if (existing != null) {
                generation++
                _ui.value = DshWebUi.Ready(DshWebUrls.startUrl(existing.localPort, null), existing.localPort, existing.remotePort, existing.leaseId, st.owned, generation)
                return@launch
            }
            _ui.value = DshWebUi.Idle(st, st.error)
            if (!autoOpened) {
                autoOpened = true
                openNow()
            }
        }
    }

    /** Confirm, start if needed, forward, load. */
    fun open() {
        job?.cancel()
        job = viewModelScope.launch { openNow() }
    }

    private suspend fun openNow() {
        if (conn.apiOrNull() == null) {
            _ui.value = DshWebUi.Offline
            return
        }
        if (!stepUpAvailable()) {
            _ui.value = DshWebUi.NoStepUpKey
            return
        }
        val before = _ui.value
        _ui.value = DshWebUi.Confirming
        var r = request(start = true) ?: run { if (_ui.value is DshWebUi.Confirming) _ui.value = idleFrom(before); return }
        val since = System.currentTimeMillis()
        while (r.forward == null) {
            when (r.status.state) {
                DshWebStates.NOT_INSTALLED -> { _ui.value = DshWebUi.NotInstalled; return }
                DshWebStates.STOPPED -> { _ui.value = DshWebUi.Idle(r.status, r.status.error); return }
            }
            _ui.value = DshWebUi.Starting(since)
            if (System.currentTimeMillis() - since > START_WAIT_MS) {
                _ui.value = DshWebUi.Failed("dsh web did not start within ${START_WAIT_MS / 1000} s")
                return
            }
            delay(POLL_MS)
            val st = runCatching { conn.apiOrNull()?.dshWebStatus() }.getOrNull() ?: continue
            if (st.state == DshWebStates.RUNNING) {
                r = request(start = false) ?: run {
                    if (_ui.value is DshWebUi.Starting) _ui.value = DshWebUi.Idle(st)
                    return
                }
            } else if (st.state != DshWebStates.STARTING) {
                r = DshWebOpen(st)
            }
        }
        attach(r)
    }

    private fun idleFrom(before: DshWebUi): DshWebUi = (before as? DshWebUi.Idle) ?: DshWebUi.Idle(null)

    /** `POST /v1/dsh-web/open` after step-up (asks once more when the cached grant expired). */
    private suspend fun request(start: Boolean): DshWebOpen? {
        repeat(2) { attempt ->
            if (gate.ensure(StepUpScopes.PORT_FORWARD).isFailure) return null
            val api = conn.apiOrNull() ?: run { _ui.value = DshWebUi.Offline; return null }
            try {
                return api.openDshWeb(start = start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == ErrorCodes.STEP_UP_REQUIRED && e.detailReason == "no_step_up_key") {
                    _ui.value = DshWebUi.NoStepUpKey
                    return null
                }
                if (e.code == ErrorCodes.STEP_UP_REQUIRED && attempt == 0) {
                    gate.invalidate(StepUpScopes.PORT_FORWARD)
                } else {
                    _ui.value = DshWebUi.Failed(message(e))
                    return null
                }
            } catch (e: Exception) {
                _ui.value = DshWebUi.Failed(message(e))
                return null
            }
        }
        return null
    }

    private suspend fun attach(r: DshWebOpen) {
        val lease = r.forward ?: return
        // Same phone port as before when this port is already forwarded; else dsh's default first.
        val reuse = forwards.active.value.firstOrNull { it.remotePort == lease.port }?.localPort
        val candidates = listOfNotNull(reuse) + DshWebUrls.LOCAL_PORTS.filter { it != reuse }
        for (lp in candidates) {
            when (val res = forwards.attach(lease, lp, PROCESS_LABEL, lease.listening)) {
                is StartResult.Started -> {
                    generation++
                    val local = res.forward.localPort
                    _ui.value = DshWebUi.Ready(DshWebUrls.startUrl(local, r.launchPath), local, lease.port, lease.id, r.status.owned, generation)
                    return
                }
                is StartResult.LocalPortBusy -> continue
                is StartResult.Failed -> { _ui.value = DshWebUi.Failed(res.message); return }
                StartResult.NoStepUpKey -> { _ui.value = DshWebUi.NoStepUpKey; return }
                StartResult.NotConfirmed -> { _ui.value = DshWebUi.Idle(r.status); return }
            }
        }
        _ui.value = DshWebUi.Failed("phone ports ${DshWebUrls.LOCAL_PORTS.first()}–${DshWebUrls.LOCAL_PORTS.last()} are all in use")
    }

    /** Stops the `dsh web` SwitchTether started (and this phone's forward to it). */
    fun stopServer() {
        val r = _ui.value as? DshWebUi.Ready ?: return
        job?.cancel()
        job = viewModelScope.launch {
            if (gate.ensure(StepUpScopes.PORT_FORWARD).isFailure) return@launch
            val api = conn.apiOrNull() ?: return@launch
            try {
                api.stopDshWeb()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = DshWebUi.Failed(message(e))
                return@launch
            }
            forwards.stop(r.leaseId)
            autoOpened = true
            check()
        }
    }

    private fun message(e: Exception): String = when (e) {
        is ApiException -> "${e.code}: ${e.body.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val PROCESS_LABEL = "DeepSeek Harness Web"
        private const val POLL_MS = 1_000L
        private const val START_WAIT_MS = 150_000L
    }
}
