package io.github.tianli0.switchtether.ui.manage

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

class ProvidersViewModel(private val conn: ConnectionController, private val repo: ManagementRepository) : ViewModel() {
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

    fun test(label: String, providerId: String) {
        val app = _state.value.app
        commands.run(label, onSuccess = { out ->
            _state.update { it.copy(testResults = it.testResults + (providerId to out.operationResult())) }
        }) { id -> repo.testProvider(app, providerId, id) }
    }
}

// ---- Usage --------------------------------------------------------------------------------------

enum class UsageRange(val days: Int) { TODAY(1), WEEK(7), MONTH(30) }

data class UsageState(
    val range: UsageRange = UsageRange.WEEK,
    val summary: Loadable<UsageSummary> = Loadable.Loading,
    val trend: Loadable<List<DailyUsage>> = Loadable.Loading,
    val models: Loadable<List<UsageBreakdownRow>> = Loadable.Loading,
    val providers: Loadable<List<UsageBreakdownRow>> = Loadable.Loading,
    val quotas: List<Pair<String, Loadable<QuotaView>>> = emptyList(),
)

class UsageViewModel(
    private val conn: ConnectionController,
    private val repo: ManagementRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    private val _state = MutableStateFlow(UsageState())
    val state: StateFlow<UsageState> = _state.asStateFlow()

    init {
        onOnline(conn) { load() }
        viewModelScope.launch { repo.changes.collect { if (it == "usage.updated") load() } }
    }

    fun setRange(r: UsageRange) {
        _state.update { it.copy(range = r) }
        load()
    }

    fun load() {
        val range = _state.value.range
        val today = LocalDate.now(zone)
        val from = today.minusDays((range.days - 1).toLong()).atStartOfDay(zone).toEpochSecond()
        val to = nowSec()
        viewModelScope.launch { val r = loadable { repo.usageSummary(from, to) }; _state.update { it.copy(summary = r) } }
        viewModelScope.launch { val r = loadable { repo.usageTrend(from, to) }; _state.update { it.copy(trend = r) } }
        viewModelScope.launch { val r = loadable { repo.usageModels(from, to) }; _state.update { it.copy(models = r) } }
        viewModelScope.launch { val r = loadable { repo.usageProviders(from, to) }; _state.update { it.copy(providers = r) } }
        viewModelScope.launch {
            val tools = listOf("claude", "codex")
            val quotas = tools.map { t -> t to loadable { repo.quota(t) } }
            _state.update { it.copy(quotas = quotas) }
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

    fun load() {
        viewModelScope.launch {
            when (kind) {
                ToggleListKind.MCP -> { val r = loadable { repo.mcpServers() }; _state.update { it.copy(items = r) } }
                ToggleListKind.SKILLS -> { val r = loadable { repo.skills() }; _state.update { it.copy(items = r) } }
                ToggleListKind.PROMPTS -> {
                    val app = _state.value.app
                    val r = loadable { repo.prompts(app) }
                    _state.update { if (it.app == app) it.copy(prompts = r) else it }
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
