package io.github.tianli0.switchtether.ui.manage

import io.github.tianli0.switchtether.remote.usage.UsageRangePreset
import io.github.tianli0.switchtether.remote.usage.isHourlyRange
import io.github.tianli0.switchtether.remote.usage.resolveUsageRange

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.domain.operationResult
import io.github.tianli0.switchtether.remote.protocol.AppToggleItem
import io.github.tianli0.switchtether.remote.protocol.DailyUsage
import io.github.tianli0.switchtether.remote.protocol.OperationResult
import io.github.tianli0.switchtether.remote.protocol.PromptView
import io.github.tianli0.switchtether.remote.protocol.ProvidersView
import io.github.tianli0.switchtether.remote.protocol.QuotaView
import io.github.tianli0.switchtether.remote.protocol.UsageBreakdownRow
import io.github.tianli0.switchtether.remote.protocol.UsageSummary
import io.github.tianli0.switchtether.ui.common.CommandTracker
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.loadable
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Default app tabs when the desktop cannot list app types (e.g. backend unavailable). */
val DEFAULT_APPS = listOf("claude", "codex", "gemini")

/** Common: refresh when the connection comes online. */
private fun ViewModel.onOnline(conn: ConnectionController, block: () -> Unit) {
    viewModelScope.launch {
        conn.state.distinctUntilChangedBy { it::class }.collect { if (it is ConnectionState.Online) block() }
    }
}

// ---- Providers ----------------------------------------------------------------------------------

data class ProvidersState(
    val apps: List<String> = DEFAULT_APPS,
    val app: String = DEFAULT_APPS.first(),
    val providers: Loadable<ProvidersView> = Loadable.Loading,
    val testResults: Map<String, OperationResult> = emptyMap(),
)

class ProvidersViewModel(
    private val conn: ConnectionController,
    private val repo: ManagementRepository,
    private val gate: io.github.tianli0.switchtether.domain.StepUpGate? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ProvidersState())
    val state: StateFlow<ProvidersState> = _state.asStateFlow()
    val commands = CommandTracker(viewModelScope) { repo.commandStatus(it) }

    init {
        onOnline(conn) { loadApps(); load() }
        viewModelScope.launch { repo.changes.collect { if (it == "provider.switched") load() } }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = runCatching { repo.apps() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@launch
            _state.update { st -> st.copy(apps = apps, app = if (st.app in apps) st.app else apps.first()) }
        }
    }

    fun selectApp(app: String) {
        _state.update { it.copy(app = app, providers = Loadable.Loading) }
        load()
    }

    fun load() {
        val app = _state.value.app
        viewModelScope.launch {
            val r = loadable { repo.providers(app) }
            _state.update { if (it.app == app) it.copy(providers = r) else it }
        }
    }

    fun switchTo(label: String, providerId: String) {
        val app = _state.value.app
        commands.run(label, onSuccess = { load() }) { id -> repo.switchProvider(app, providerId, id) }
    }

    /**
     * Write-only credential update behind a `config_write` step-up (value never read back).
     * [stepUpDone] gets false when the biometric confirmation was cancelled or failed: nothing was
     * sent, and the dialog keeps the typed value so the user can try again.
     */
    fun setCredential(label: String, providerId: String, field: String, value: String, stepUpDone: (Boolean) -> Unit = {}) {
        val app = _state.value.app
        viewModelScope.launch {
            if (gate != null && gate.ensure(io.github.tianli0.switchtether.remote.protocol.StepUpScopes.CONFIG_WRITE).isFailure) {
                stepUpDone(false)
                return@launch
            }
            stepUpDone(true)
            commands.run(label, onSuccess = { load() }) { id -> repo.setCredential(app, providerId, field, value, id) }
        }
    }

    fun test(label: String, providerId: String) {
        val app = _state.value.app
        commands.run(label, onSuccess = { out ->
            _state.update { it.copy(testResults = it.testResults + (providerId to out.operationResult())) }
        }) { id -> repo.testProvider(app, providerId, id) }
    }
}

// ---- Usage --------------------------------------------------------------------------------------

enum class UsageMetric { TOKENS, COST, REQUESTS }

/** Same presets, default (today), app filter and range math as the desktop Usage page. */
data class UsageState(
    val preset: UsageRangePreset = UsageRangePreset.TODAY,
    /** null = all apps (the desktop's "All"). */
    val app: String? = null,
    val metric: UsageMetric = UsageMetric.TOKENS,
    val fromSec: Long = 0,
    val toSec: Long = 0,
    val summary: Loadable<UsageSummary> = Loadable.Loading,
    val trend: Loadable<List<DailyUsage>> = Loadable.Loading,
    val models: Loadable<List<UsageBreakdownRow>> = Loadable.Loading,
    val providers: Loadable<List<UsageBreakdownRow>> = Loadable.Loading,
    val quotas: List<Pair<String, Loadable<QuotaView>>> = emptyList(),
    /** Request log rows loaded so far (pages appended); null = the desktop has no log API. */
    val logs: List<io.github.tianli0.switchtether.remote.protocol.RequestLog>? = emptyList(),
    val logsTotal: Long = 0,
    val logsPage: Int = -1,
    val logsLoading: Boolean = false,
    /** The last page request failed (shown with a retry instead of an endless spinner). */
    val logsError: Boolean = false,
    /** Request shown in the detail sheet (refreshed from `/v1/usage/logs/{id}`). */
    val selectedLog: io.github.tianli0.switchtether.remote.protocol.RequestLog? = null,
    /** Last session-log scan on the computer (epoch ms), null if unknown. */
    val lastSyncMs: Long? = null,
    /** Apps the desktop hides (`visibleApps` false) are not offered as filters. */
    val hiddenApps: Set<String> = emptySet(),
    /** Zone the ranges and labels use: the desktop's (falls back to the phone's). */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Usage per project; null = the desktop has no project API (section hidden). */
    val projects: Loadable<io.github.tianli0.switchtether.remote.protocol.ProjectUsageView>? = Loadable.Loading,
) {
    val hourly: Boolean get() = isHourlyRange(fromSec, toSec)
    val logsHaveMore: Boolean get() = logs != null && logs.size < logsTotal
}

class UsageViewModel(
    private val conn: ConnectionController,
    private val repo: ManagementRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val _state = MutableStateFlow(UsageState())
    val state: StateFlow<UsageState> = _state.asStateFlow()
    private var logsJob: kotlinx.coroutines.Job? = null

    /** Bumped by every range load: answers for an earlier preset / app must not overwrite the current one. */
    private var rangeGen = 0

    init {
        onOnline(conn) { load() }
        viewModelScope.launch { repo.changes.collect { if (it == "usage.updated") load() } }
    }

    fun setPreset(p: UsageRangePreset) {
        _state.update { it.copy(preset = p) }
        load()
    }

    fun setApp(app: String?) {
        _state.update { it.copy(app = app) }
        load()
    }

    fun setMetric(m: UsageMetric) = _state.update { it.copy(metric = m) }

    /** The desktop's zone from `/v1/status` (older desktops: this phone's zone). */
    private fun desktopZone(): ZoneId {
        val status = (conn.state.value as? io.github.tianli0.switchtether.connection.ConnectionState.Online)?.status
        return io.github.tianli0.switchtether.remote.protocol.desktopZone(status?.timeZone, status?.utcOffsetMinutes, zone)
    }

    fun load() {
        viewModelScope.launch { loadRange() }
        viewModelScope.launch {
            val sync = runCatching { repo.usageLastSyncMs() }.getOrNull()
            val visible = runCatching { repo.visibleApps() }.getOrDefault(emptyMap())
            _state.update { it.copy(lastSyncMs = sync, hiddenApps = visible.filterValues { v -> !v }.keys) }
        }
    }

    private suspend fun loadRange() {
        val gen = ++rangeGen
        val st = _state.value
        val z = desktopZone()
        // "全部" starts at the desktop's first usage day (older desktops: the epoch).
        val first = if (st.preset == UsageRangePreset.ALL) runCatching { repo.usageFirstDateMs(st.app) }.getOrNull() else null
        if (gen != rangeGen) return // a newer preset / app was chosen while the first date loaded
        val (from, to) = resolveUsageRange(st.preset, nowMs(), z, first)
        val app = st.app
        // A request for the previous range must not hold the slot (it would be dropped on return and
        // leave the list on "loading" forever).
        logsJob?.cancel()
        _state.update { it.copy(fromSec = from, toSec = to, zone = z, logs = if (it.logs == null) null else emptyList(), logsTotal = 0, logsPage = -1, logsLoading = false, logsError = false) }
        loadMoreLogs()
        // The total another screen (Home) already read for this range shows until the fresh one is back.
        repo.summaries.value[io.github.tianli0.switchtether.domain.UsageSummaryKey(from, app)]?.let { cached ->
            _state.update { if (gen == rangeGen) it.copy(summary = Loadable.Ready(cached)) else it }
        }
        viewModelScope.launch { val r = loadable { repo.usageSummary(from, to, app) }; _state.update { if (gen == rangeGen) it.copy(summary = r) else it } }
        viewModelScope.launch { val r = loadable { repo.usageTrend(from, to, app) }; _state.update { if (gen == rangeGen) it.copy(trend = r) else it } }
        // The desktop sorts both tables by request count (descending) by default.
        viewModelScope.launch { val r = loadable { repo.usageModels(from, to, app).sortedByDescending { it.requests } }; _state.update { if (gen == rangeGen) it.copy(models = r) else it } }
        viewModelScope.launch { val r = loadable { repo.usageProviders(from, to, app).sortedByDescending { it.requests } }; _state.update { if (gen == rangeGen) it.copy(providers = r) else it } }
        viewModelScope.launch {
            val r = loadProjects { repo.usageProjects(from, to, app) }
            _state.update { if (gen == rangeGen) it.copy(projects = r) else it }
        }
        viewModelScope.launch {
            val tools = io.github.tianli0.switchtether.domain.QUOTA_TOOLS
            // Start from the quotas Home already read; replace each as its fresh answer arrives.
            val known = repo.quotas.value
            if (_state.value.quotas.isEmpty()) {
                _state.update { it.copy(quotas = tools.map { t -> t to (known[t]?.let { q -> Loadable.Ready(q) } ?: Loadable.Loading) }) }
            }
            val quotas = tools.map { t -> t to loadable { repo.quota(t) } }
            _state.update { it.copy(quotas = quotas) }
        }
    }

    /** Next page of the request log (20 rows); an older desktop without the API hides the section. */
    fun loadMoreLogs() {
        val st = _state.value
        if (st.logs == null || st.logsLoading || (st.logsPage >= 0 && !st.logsHaveMore)) return
        val page = st.logsPage + 1
        val (from, to, app) = Triple(st.fromSec, st.toSec, st.app)
        _state.update { it.copy(logsLoading = true, logsError = false) }
        logsJob = viewModelScope.launch {
            val r = runCatching { repo.usageLogs(from, to, app, page) }
            if (r.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            _state.update { cur ->
                if (cur.fromSec != from || cur.app != app) return@update cur // superseded; the new range loads its own page
                r.fold(
                    onSuccess = { p -> cur.copy(logs = cur.logs.orEmpty() + p.data, logsTotal = p.total, logsPage = page, logsLoading = false) },
                    onFailure = { e ->
                        val missing = generateSequence(e) { it.cause }.any { (it as? io.github.tianli0.switchtether.remote.api.ApiException)?.httpStatus == 404 } || e is UnsupportedOperationException
                        cur.copy(logs = if (missing) null else cur.logs, logsLoading = false, logsError = !missing)
                    },
                )
            }
        }
    }

    fun openLog(log: io.github.tianli0.switchtether.remote.protocol.RequestLog?) {
        _state.update { it.copy(selectedLog = log) }
        if (log == null) return
        viewModelScope.launch {
            val fresh = runCatching { repo.usageLog(log.requestId) }.getOrNull() ?: return@launch
            _state.update { if (it.selectedLog?.requestId == log.requestId) it.copy(selectedLog = fresh) else it }
        }
    }
}

// ---- MCP / skills / prompts ---------------------------------------------------------------------

enum class ToggleListKind { MCP, SKILLS, PROMPTS }

data class ToggleListState(
    val kind: ToggleListKind,
    val app: String = DEFAULT_APPS.first(),
    val items: Loadable<List<AppToggleItem>> = Loadable.Loading,
    val prompts: Loadable<List<PromptView>> = Loadable.Loading,
)

class ToggleListViewModel(
    private val kind: ToggleListKind,
    private val conn: ConnectionController,
    private val repo: ManagementRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ToggleListState(kind))
    val state: StateFlow<ToggleListState> = _state.asStateFlow()
    val commands = CommandTracker(viewModelScope) { repo.commandStatus(it) }

    init {
        onOnline(conn) { load() }
    }

    fun selectApp(app: String) {
        _state.update { it.copy(app = app) }
        if (kind == ToggleListKind.PROMPTS) load()
    }

    /** Latest load wins: an older answer landing after a newer one (quick toggles) is dropped. */
    private var loadGen = 0

    fun load() {
        val gen = ++loadGen
        viewModelScope.launch {
            when (kind) {
                ToggleListKind.MCP -> { val r = loadable { repo.mcpServers() }; _state.update { if (gen == loadGen) it.copy(items = r) else it } }
                ToggleListKind.SKILLS -> { val r = loadable { repo.skills() }; _state.update { if (gen == loadGen) it.copy(items = r) else it } }
                ToggleListKind.PROMPTS -> {
                    val app = _state.value.app
                    val r = loadable { repo.prompts(app) }
                    _state.update { if (gen == loadGen && it.app == app) it.copy(prompts = r) else it }
                }
            }
        }
    }

    fun toggle(label: String, id: String, enabled: Boolean) {
        val app = _state.value.app
        commands.run(label, onSuccess = { load() }) { cmd ->
            when (kind) {
                ToggleListKind.MCP -> repo.toggleMcp(id, app, enabled, cmd)
                ToggleListKind.SKILLS -> repo.toggleSkill(id, app, enabled, cmd)
                ToggleListKind.PROMPTS -> repo.enablePrompt(app, id, cmd)
            }
        }
    }
}

/** Loads usage per project; null when the desktop does not have the API (404 / not implemented). */
internal suspend fun loadProjects(
    block: suspend () -> io.github.tianli0.switchtether.remote.protocol.ProjectUsageView,
): Loadable<io.github.tianli0.switchtether.remote.protocol.ProjectUsageView>? {
    val r = runCatching { block() }
    r.exceptionOrNull()?.let { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        val missing = generateSequence(e) { it.cause }.any { (it as? io.github.tianli0.switchtether.remote.api.ApiException)?.httpStatus == 404 } || e is UnsupportedOperationException
        if (missing) return null
    }
    return loadable { r.getOrThrow() }
}

// ---- One project's usage ------------------------------------------------------------------------

data class ProjectUsageState(
    val project: String,
    val preset: UsageRangePreset = UsageRangePreset.DAYS_30,
    /** null = the desktop has no project API. */
    val usage: Loadable<io.github.tianli0.switchtether.remote.protocol.ProjectUsage?>? = Loadable.Loading,
)

/** `Route.ProjectUsage`: one project's cost and its sessions, most expensive first. */
class ProjectUsageViewModel(
    project: String,
    private val conn: ConnectionController,
    private val repo: ManagementRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectUsageState(project))
    val state: StateFlow<ProjectUsageState> = _state.asStateFlow()
    private var gen = 0

    init {
        onOnline(conn) { load() }
    }

    fun setPreset(p: UsageRangePreset) {
        _state.update { it.copy(preset = p) }
        load()
    }

    fun load() {
        val g = ++gen
        val st = _state.value
        _state.update { it.copy(usage = if (it.usage == null) null else Loadable.Loading) }
        viewModelScope.launch {
            val status = (conn.state.value as? io.github.tianli0.switchtether.connection.ConnectionState.Online)?.status
            val z = io.github.tianli0.switchtether.remote.protocol.desktopZone(status?.timeZone, status?.utcOffsetMinutes, zone)
            val first = if (st.preset == UsageRangePreset.ALL) runCatching { repo.usageFirstDateMs(null) }.getOrNull() else null
            val (from, to) = resolveUsageRange(st.preset, nowMs(), z, first)
            val r = loadProjects { repo.usageProjects(from, to, null, st.project) }
            val one = when (r) {
                null -> null
                is Loadable.Ready -> Loadable.Ready(r.value.projects.firstOrNull())
                is Loadable.Failed -> r
                Loadable.Loading -> Loadable.Loading
            }
            _state.update { if (g == gen) it.copy(usage = one) else it }
        }
    }
}
