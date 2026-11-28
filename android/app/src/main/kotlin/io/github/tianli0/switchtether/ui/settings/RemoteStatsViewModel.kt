package io.github.tianli0.switchtether.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.protocol.RemoteStatsView
import io.github.tianli0.switchtether.remote.protocol.desktopZone
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.loadable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Periods of the statistics page, in the desktop's time zone (weeks start on Monday). */
enum class StatsPreset { THIS_WEEK, LAST_WEEK, THIS_MONTH, ALL }

/**
 * `[from, to]` in epoch seconds for [preset] at [nowMs] in [zone]; `from` null = since the first
 * day with statistics.
 */
fun statsRange(preset: StatsPreset, nowMs: Long, zone: ZoneId): Pair<Long?, Long> {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val today = now.toLocalDate()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val nowSec = nowMs / 1000
    return when (preset) {
        StatsPreset.THIS_WEEK -> monday.atStartOfDay(zone).toEpochSecond() to nowSec
        StatsPreset.LAST_WEEK -> monday.minusWeeks(1).atStartOfDay(zone).toEpochSecond() to monday.atStartOfDay(zone).toEpochSecond() - 1
        StatsPreset.THIS_MONTH -> today.withDayOfMonth(1).atStartOfDay(zone).toEpochSecond() to nowSec
        StatsPreset.ALL -> null to nowSec
    }
}

data class RemoteStatsState(
    val preset: StatsPreset = StatsPreset.THIS_WEEK,
    /** False on a desktop without phone statistics; null until known. */
    val supported: Boolean? = null,
    val stats: Loadable<RemoteStatsView> = Loadable.Loading,
)

/** `Route.RemoteStats`: what the paired phones did through SwitchTether. */
class RemoteStatsViewModel(
    private val conn: ConnectionController,
    private val repo: ManagementRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteStatsState())
    val state: StateFlow<RemoteStatsState> = _state.asStateFlow()
    private var gen = 0

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { if (it is ConnectionState.Online) load() }
        }
    }

    fun setPreset(p: StatsPreset) {
        if (p == _state.value.preset) return
        _state.update { it.copy(preset = p) }
        load()
    }

    fun load() {
        val g = ++gen
        val preset = _state.value.preset
        _state.update { s ->
            val prev = s.stats
            s.copy(stats = if (prev is Loadable.Ready) prev.copy(refreshing = true) else Loadable.Loading)
        }
        viewModelScope.launch {
            val supported = _state.value.supported ?: runCatching { repo.supportsRemoteStats() }.getOrNull()
            if (supported == false) {
                if (g == gen) _state.update { it.copy(supported = false) }
                return@launch
            }
            val status = (conn.state.value as? ConnectionState.Online)?.status
            val z = desktopZone(status?.timeZone, status?.utcOffsetMinutes, zone)
            val (from, to) = statsRange(preset, nowMs(), z)
            val result = runCatching { repo.remoteStats(from, to) }
            val e = result.exceptionOrNull()
            if (e is kotlinx.coroutines.CancellationException) throw e
            // An older desktop (404) has no statistics.
            val missing = e is UnsupportedOperationException || (e as? ApiException)?.httpStatus == 404
            val r = loadable { result.getOrThrow() }
            if (g != gen) return@launch
            _state.update {
                when {
                    missing -> it.copy(supported = false)
                    else -> it.copy(supported = supported ?: it.supported, stats = r)
                }
            }
        }
    }
}
