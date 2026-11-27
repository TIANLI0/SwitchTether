package io.github.tianli0.switchtether.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.preferencesDataStoreFile
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


/**
 * The app's one preferences file, shared by the UI process and the `:link` process (docs/ANDROID_UI.md
 * §38): a multi-process DataStore, so a write in one process (pairing, a setting, the relay refresh)
 * reaches the other's flows. Same file and format as the single-process `preferencesDataStore`
 * it replaces, so nothing is migrated. One instance per process.
 */
internal object SharedPreferencesStore {
    @Volatile private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: MultiProcessDataStoreFactory.create(
            serializer = PreferencesFileSerializer,
            produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
        ).also { instance = it }
    }

    const val FILE_NAME = "switchtether"
}

private val Context.dataStore: DataStore<Preferences> get() = SharedPreferencesStore.get(this)

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

    /** Pages follow the finger during the back gesture (Android's predictive back). Default on. */
    val predictiveBack: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)
    suspend fun setPredictiveBack(enabled: Boolean) {}
}

/** Unsent message drafts (local only; never sent automatically). */
interface DraftStore {
    fun draft(sessionId: String): Flow<String>
    suspend fun setDraft(sessionId: String, text: String)
    suspend fun clearAll()
}

/**
 * Small settings the UI process and the `:link` process both use. They lived in per-process
 * SharedPreferences, whose in-memory copy never sees the other process's writes.
 */
interface LinkPrefs {
    /** Companion: keep the background connection only while the bound computer is nearby. */
    val companionNearbyOnly: Flow<Boolean>
    suspend fun setCompanionNearbyOnly(value: Boolean)

    /** Companion: last presence the system reported (true until the first "gone"). */
    val companionPresent: Flow<Boolean>
    suspend fun setCompanionPresent(value: Boolean)

    /** Live Updates: tasks started from the phone are pinned automatically. */
    val autoPinPhoneSessions: Flow<Boolean>
    suspend fun setAutoPinPhoneSessions(value: Boolean)

    /**
     * Server id whose pairing the `:link` process ended (the desktop revoked this phone) while
     * the UI process may not have been running: the UI clears what it keeps for it at next start.
     */
    val pendingWipe: Flow<String?>
    suspend fun setPendingWipe(serverId: String?)

    /** Copies the values the SharedPreferences of older builds kept (idempotent; only missing keys). */
    suspend fun migrateLegacy(context: Context)
}

class DataStoreStores(private val context: Context) : PairingStore, SettingsStore, DraftStore, LinkPrefs {
    private val store get() = context.dataStore

    private val keyDesktop = stringPreferencesKey("paired_desktop_v1")
    private val keyTheme = stringPreferencesKey("theme_mode")
    private val keyBackground = booleanPreferencesKey("background_connection")
    private val keyPredictiveBack = booleanPreferencesKey("predictive_back")
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

    override val predictiveBack: Flow<Boolean> = store.data.map { it[keyPredictiveBack] ?: true }

    override suspend fun setPredictiveBack(enabled: Boolean) {
        store.edit { it[keyPredictiveBack] = enabled }
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

    private val keyNearbyOnly = booleanPreferencesKey("companion_nearby_only")
    private val keyPresent = booleanPreferencesKey("companion_present")
    private val keyAutoPin = booleanPreferencesKey("live_auto_pin_new")
    private val keyPendingWipe = stringPreferencesKey("pending_wipe_server")

    override val companionNearbyOnly: Flow<Boolean> = store.data.map { it[keyNearbyOnly] ?: false }
    override suspend fun setCompanionNearbyOnly(value: Boolean) { store.edit { it[keyNearbyOnly] = value } }
    override val companionPresent: Flow<Boolean> = store.data.map { it[keyPresent] ?: true }
    override suspend fun setCompanionPresent(value: Boolean) { store.edit { it[keyPresent] = value } }
    override val autoPinPhoneSessions: Flow<Boolean> = store.data.map { it[keyAutoPin] ?: true }
    override suspend fun setAutoPinPhoneSessions(value: Boolean) { store.edit { it[keyAutoPin] = value } }
    override val pendingWipe: Flow<String?> = store.data.map { it[keyPendingWipe] }
    override suspend fun setPendingWipe(serverId: String?) {
        store.edit { if (serverId == null) it.remove(keyPendingWipe) else it[keyPendingWipe] = serverId }
    }

    override suspend fun migrateLegacy(context: Context) {
        val companion = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
        val live = context.getSharedPreferences("live_pins", Context.MODE_PRIVATE)
        store.edit { p ->
            if (p[keyNearbyOnly] == null && companion.contains("nearby_only")) p[keyNearbyOnly] = companion.getBoolean("nearby_only", false)
            if (p[keyPresent] == null && companion.contains("present")) p[keyPresent] = companion.getBoolean("present", true)
            if (p[keyAutoPin] == null && live.contains("auto_pin_new")) p[keyAutoPin] = live.getBoolean("auto_pin_new", true)
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
