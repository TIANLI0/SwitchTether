package io.github.tianli0.switchtether.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * The one paired desktop. Contains NO secrets: private keys stay in the Android Keystore and are
 * referenced only by alias; the pairing secret is never stored.
 */
@Serializable
data class PairedDesktop(
    val serverId: String,
    val name: String,
    /** Lowercase hex SHA-256 of the desktop certificate (TLS pin). */
    val fingerprint: String,
    val hosts: List<String>,
    val lastGoodHost: String?,
    val port: Int,
    val deviceId: String,
    val identityAlias: String,
    val stepUpAlias: String?,
    val stepUpEnabled: Boolean,
    val pairedAtMs: Long,
    /** Relay of this desktop (pairing link `r`, docs/RELAY.md); used only when no direct host answers. */
    val relayUrl: String? = null,
    /** Legacy plaintext token from early builds: never written any more, never used. */
    val relayToken: String? = null,
    /** Relay token + relay key, encrypted with a Keystore key (see KeystoreRelaySecrets). */
    val relaySecret: String? = null,
    /** Wake-on-LAN targets last reported by the desktop (`GET /v1/status` → `wake`). Not secret. */
    val wake: List<io.github.tianli0.switchtether.remote.wake.WakeTarget> = emptyList(),
) {
    /** Host candidates with the last successful one first. */
    fun orderedHosts(): List<String> = listOfNotNull(lastGoodHost) + hosts.filter { it != lastGoodHost }
}


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "switchtether")

interface PairingStore {
    val desktop: Flow<PairedDesktop?>
    suspend fun current(): PairedDesktop?
    suspend fun save(desktop: PairedDesktop)
    suspend fun clear()
}

interface SettingsStore {
    val themeMode: Flow<ThemeMode>
    val backgroundConnection: Flow<Boolean>
    val notifyPrefs: Flow<NotifyPrefs>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setBackgroundConnection(enabled: Boolean)
    suspend fun updateNotifyPrefs(transform: (NotifyPrefs) -> NotifyPrefs)
}

/** Unsent message drafts (local only; never sent automatically). */
interface DraftStore {
    fun draft(sessionId: String): Flow<String>
    suspend fun setDraft(sessionId: String, text: String)
    suspend fun clearAll()
}

class DataStoreStores(private val context: Context) : PairingStore, SettingsStore, DraftStore {
    private val store get() = context.dataStore

    private val keyDesktop = stringPreferencesKey("paired_desktop_v1")
    private val keyTheme = stringPreferencesKey("theme_mode")
    private val keyBackground = booleanPreferencesKey("background_connection")
    private val keyNotifyOn = stringSetPreferencesKey("notify_enabled_types")
    /** Types that existed when [keyNotifyOn] was written: types added later get their default. */
    private val keyNotifyKnown = stringSetPreferencesKey("notify_known_types")
    private val keyMutedAgents = stringSetPreferencesKey("notify_muted_agents")
    private val keyMutedProjects = stringSetPreferencesKey("notify_muted_projects")
    private val keyQuiet = stringPreferencesKey("notify_quiet") // "on|start|end"
    private val draftPrefix = "draft:"

    override val desktop: Flow<PairedDesktop?> = store.data.map { prefs ->
        prefs[keyDesktop]?.let { runCatching { ProtocolJson.decodeFromString(PairedDesktop.serializer(), it) }.getOrNull() }
    }

    override suspend fun current(): PairedDesktop? = desktop.first()

    override suspend fun save(desktop: PairedDesktop) {
        store.edit { it[keyDesktop] = ProtocolJson.encodeToString(PairedDesktop.serializer(), desktop) }
    }

    override suspend fun clear() {
        store.edit { it.remove(keyDesktop) }
    }

    override val themeMode: Flow<ThemeMode> = store.data.map { p ->
        p[keyTheme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM
    }

    /** Default ON (best-effort continuous monitoring), user can turn it off. */
    override val backgroundConnection: Flow<Boolean> = store.data.map { it[keyBackground] ?: true }

    override val notifyPrefs: Flow<NotifyPrefs> = store.data.map { p ->
        val d = NotifyPrefs()
        val quiet = p[keyQuiet]?.split('|')
        NotifyPrefs(
            enabled = p[keyNotifyOn]?.let { saved ->
                val known = p[keyNotifyKnown] ?: LEGACY_NOTIFY_TYPES
                val chosen = saved.mapNotNull { v -> NotifyType.entries.firstOrNull { it.name == v } }.toSet()
                chosen + d.enabled.filter { it.name !in known }
            } ?: d.enabled,
            mutedAgents = p[keyMutedAgents].orEmpty(),
            mutedProjects = p[keyMutedProjects].orEmpty(),
            quietHoursEnabled = quiet?.getOrNull(0) == "1",
            quietStartMin = quiet?.getOrNull(1)?.toIntOrNull() ?: d.quietStartMin,
            quietEndMin = quiet?.getOrNull(2)?.toIntOrNull() ?: d.quietEndMin,
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[keyTheme] = mode.name }
    }

    override suspend fun setBackgroundConnection(enabled: Boolean) {
        store.edit { it[keyBackground] = enabled }
    }

    override suspend fun updateNotifyPrefs(transform: (NotifyPrefs) -> NotifyPrefs) {
        val n = transform(notifyPrefs.first())
        store.edit { p ->
            p[keyNotifyOn] = n.enabled.map { it.name }.toSet()
            p[keyNotifyKnown] = NotifyType.entries.map { it.name }.toSet()
            p[keyMutedAgents] = n.mutedAgents
            p[keyMutedProjects] = n.mutedProjects
            p[keyQuiet] = "${if (n.quietHoursEnabled) 1 else 0}|${n.quietStartMin}|${n.quietEndMin}"
        }
    }

    override fun draft(sessionId: String): Flow<String> =
        store.data.map { it[stringPreferencesKey(draftPrefix + sessionId)].orEmpty() }

    override suspend fun setDraft(sessionId: String, text: String) {
        store.edit {
            val k = stringPreferencesKey(draftPrefix + sessionId)
            if (text.isBlank()) it.remove(k) else it[k] = text.take(20_000)
        }
    }

    override suspend fun clearAll() {
        store.edit { p -> p.asMap().keys.filter { it.name.startsWith(draftPrefix) }.forEach { p.remove(it) } }
    }
}

/** Notification types that existed before `notify_known_types` was stored (Phase D). */
private val LEGACY_NOTIFY_TYPES: Set<String> =
    NotifyType.entries.map { it.name }.toSet() - setOf("SESSION_EXITED", "TERMINAL_EXITED", "LIVE_UPDATE")
