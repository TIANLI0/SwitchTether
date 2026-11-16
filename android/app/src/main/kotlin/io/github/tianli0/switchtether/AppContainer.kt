package io.github.tianli0.switchtether

import android.content.Context
import android.os.Build
import androidx.fragment.app.FragmentActivity
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.NetworkMonitor
import io.github.tianli0.switchtether.connection.NotConnectedException
import io.github.tianli0.switchtether.data.DataStoreStores
import io.github.tianli0.switchtether.data.DraftStore
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.data.SettingsStore
import io.github.tianli0.switchtether.domain.DefaultPairingRepository
import io.github.tianli0.switchtether.domain.KeyProvider
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.domain.PairingRepository
import io.github.tianli0.switchtether.domain.RemoteManagementRepository
import io.github.tianli0.switchtether.domain.RemoteSessionRepository
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.protocol.StepUpGrant
import io.github.tianli0.switchtether.remote.stepup.StepUpPayload
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.security.BiometricSigner
import io.github.tianli0.switchtether.security.KeystoreKeys
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual dependency container (no DI framework). One per process. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob())
    val keys = KeystoreKeys(context)
    private val stores = DataStoreStores(context)
    val pairingStore: PairingStore = stores
    val settingsStore: SettingsStore = stores
    val draftStore: DraftStore = stores

    /** Shared base client: pools/dispatcher reused by every pinned client built from it. */
    private val baseClient = OkHttpClient()

    /** In-memory caches only (cleared by Settings → Clear cache). Transcripts never touch disk. */
    private val _cacheGeneration = MutableStateFlow(0)
    val cacheGeneration: StateFlow<Int> = _cacheGeneration.asStateFlow()

    val keyProvider: KeyProvider = object : KeyProvider {
        override fun isDeviceSecure() = keys.isDeviceSecure()
        override fun createIdentity(): ClientIdentity = keys.createIdentity()
        override fun loadIdentity(alias: String): ClientIdentity? = keys.loadIdentity(alias)
        override fun createStepUpKey(): String? = keys.createStepUpKey()
        override fun stepUpPublicKeySpki(alias: String): String? = keys.stepUpPublicKeySpki(alias)
        override fun delete(alias: String?) = keys.delete(alias)
    }

    /** Android 17 local network permission state. */
    val localNetwork = io.github.tianli0.switchtether.connection.LocalNetworkAccess(context)

    /** LAN discovery (NSD); runs only on demand. */
    val discovery = io.github.tianli0.switchtether.connection.NsdDiscovery(context)

    val connection = ConnectionManager(
        scope = appScope,
        pairingStore = pairingStore,
        identityLoader = { keys.loadIdentity(it) },
        onRevoked = { desktop, _ -> wipeLocal(desktop) },
        baseClient = baseClient,
        discover = { fp, timeoutMs -> discovery.find(fp, timeoutMs) },
        localNetworkAllowed = { localNetwork.isGranted() },
        relaySecrets = io.github.tianli0.switchtether.connection.KeystoreRelaySecrets,
    )

    val sessions: SessionRepository = RemoteSessionRepository(connection)
    val management: ManagementRepository = RemoteManagementRepository(connection)

    val pairing: PairingRepository = DefaultPairingRepository(
        keys = keyProvider,
        store = pairingStore,
        baseClient = baseClient,
        relaySecrets = io.github.tianli0.switchtether.connection.KeystoreRelaySecrets,
        onReplaced = { previous ->
            // Best effort: the old desktop keeps a record until revoked there; wipe our keys for it.
            keys.delete(previous.identityAlias)
            keys.delete(previous.stepUpAlias)
        },
    )

    private val network = NetworkMonitor(context) { connection.onNetworkAvailable() }

    @Volatile var isForeground: Boolean = false
        private set
    @Volatile private var serviceRunning = false

    val notifyPrefsState = settingsStore.notifyPrefs.stateIn(appScope, SharingStarted.Eagerly, io.github.tianli0.switchtether.remote.notify.NotifyPrefs())
    private val notifier = io.github.tianli0.switchtether.notify.Notifier(context, appScope, connection, notifyPrefsState, { isForeground }) { id -> liveUpdates.isPinned(id) }

    /** Working/waiting live sessions (keeps the monitor service's CPU wake lock). */
    val activeSessions get() = notifier.activeSessions

    /** Home-screen widget data (memory only). */
    val widget = io.github.tianli0.switchtether.widget.StatusWidgetUpdater(context, appScope, connection, sessions)
    val money = io.github.tianli0.switchtether.domain.MoneySettings(context, appScope, connection)

    /** Session detail currently on screen (Android 17 Handoff offers it to the user's other device). */
    val handoffSession = MutableStateFlow<String?>(null)

    /** Paired desktop's server id, kept in memory for synchronous callers (Handoff). */
    @Volatile var pairedServerIdCache: String? = null
        private set

    init {
        appScope.launch { pairingStore.desktop.collect { pairedServerIdCache = it?.serverId } }
    }

    /** Pinned tasks → Android 16 Live Updates (ColorOS 16 Fluid Cloud / 流体云). */
    val liveUpdates: io.github.tianli0.switchtether.notify.LiveUpdates by lazy {
        io.github.tianli0.switchtether.notify.LiveUpdates(
            context = context,
            scope = appScope,
            conn = connection,
            pairingStore = pairingStore,
            channel = io.github.tianli0.switchtether.notify.AndroidLiveUpdateChannel(context),
            resolveApproval = { s, r, d, c -> sessions.resolveApproval(s, r, d, c) },
            enabled = { io.github.tianli0.switchtether.remote.notify.NotifyType.LIVE_UPDATE in notifyPrefsState.value.enabled },
        )
    }

    /** Self-update (GitHub releases; checksum + signing-certificate verified). */
    val updates = io.github.tianli0.switchtether.update.UpdateManager(context)

    /** Set by the "new version" notification: the main shell shows the update sheet. */
    val showUpdateRequest = MutableStateFlow(false)

    /** The phone itself interrupted/stopped a session or killed a terminal (suppresses the echo notification). */
    fun noteLocalAction(target: String, action: io.github.tianli0.switchtether.remote.notify.LocalAction) = notifier.noteLocalAction(target, action)

    /** The new-session sheet was left for the folder picker page; reopen it when the picker closes. */
    val newSessionReopen = MutableStateFlow(false)

    /** Session id requested by a notification tap (consumed by the main shell). */
    val openSessionRequest = MutableStateFlow<String?>(null)

    /** Terminal id to show in Workspace → Terminals (from a session mirrored from a terminal). */
    val openTerminalRequest = MutableStateFlow<String?>(null)

    fun onForeground() {
        isForeground = true
        network.start()
        connection.start()
        appScope.launch {
            // A pinned task keeps the connection alive (its Live Update must follow the session).
            if (pairingStore.current() != null && (settingsStore.backgroundConnection.first() || liveUpdates.pins.value.isNotEmpty())) {
                io.github.tianli0.switchtether.connection.ReconnectWorker.schedulePeriodic(context)
                io.github.tianli0.switchtether.notify.MonitorService.start(context)
            }
        }
    }

    fun onBackground() {
        isForeground = false
        // With the monitor service running the connection stays up; otherwise nothing runs in background.
        if (!serviceRunning) {
            network.stop()
            connection.stop()
        }
    }

    fun onServiceKeepAlive() {
        serviceRunning = true
        network.start()
        connection.start()
    }

    fun onServiceStopped() {
        serviceRunning = false
        if (!isForeground) {
            network.stop()
            connection.stop()
        }
    }

    /** Settings toggle "Stay connected in background". */
    fun setBackgroundConnection(enabled: Boolean) {
        appScope.launch {
            settingsStore.setBackgroundConnection(enabled)
            if (enabled && pairingStore.current() != null) {
                io.github.tianli0.switchtether.connection.ReconnectWorker.schedulePeriodic(context)
                io.github.tianli0.switchtether.notify.MonitorService.start(context)
            }
            if (!enabled) {
                if (liveUpdates.pins.value.isEmpty()) {
                    io.github.tianli0.switchtether.connection.ReconnectWorker.cancel(context)
                }
                io.github.tianli0.switchtether.notify.MonitorService.stop(context)
            }
        }
    }

    /**
     * Set right after a successful `POST /v1/pair` so the onboarding flow can show the 6-digit
     * verification code before the main UI replaces it; cleared by [onPaired].
     */
    val pendingVerification = MutableStateFlow<io.github.tianli0.switchtether.domain.PairingResult?>(null)

    /** A pairing link delivered by a VIEW intent; consumed by the onboarding screen. */
    val incomingPairingLink = MutableStateFlow<String?>(null)

    /** After the user confirmed the verification code: connect to the new desktop. */
    fun onPaired() {
        pendingVerification.value = null
        connection.reset()
        connection.start()
    }

    /**
     * Unpair: ask the desktop to revoke this phone (`DELETE /v1/device`) when reachable, then wipe
     * pairing state and Keystore keys regardless. Returns whether the desktop confirmed.
     */
    suspend fun unpair(): Boolean {
        val desktop = pairingStore.current() ?: return false
        // Stop the connection loop first so the desktop's revoke (close 4403) is not reported as
        // "revoked by the desktop" after a user-initiated unpair.
        val api = connection.apiOrNull()
        connection.reset()
        val remoteOk = runCatching { (api ?: throw NotConnectedException()).unpairSelf() }.isSuccess
        wipeLocal(desktop)
        return remoteOk
    }

    private suspend fun wipeLocal(desktop: PairedDesktop) {
        io.github.tianli0.switchtether.connection.ReconnectWorker.cancel(context)
        io.github.tianli0.switchtether.notify.MonitorService.stop(context)
        pairingStore.clear()
        keys.delete(desktop.identityAlias)
        keys.delete(desktop.stepUpAlias)
        keys.deleteAllExcept(emptySet())
        draftStore.clearAll()
        money.clear()
        clearCaches()
        stepUpGate.clear()
    }

    fun clearCaches() {
        _cacheGeneration.value += 1
    }

    fun clearCachesAndDrafts() {
        clearCaches()
        appScope.launch { draftStore.clearAll() }
    }

    /**
     * Step-up (§4): challenge → BiometricPrompt-gated signature with the Keystore step-up key →
     * verify. Used by Settings → "Verify biometric confirmation" in Phase A; terminal/file writes
     * use it in Phase B.
     */
    suspend fun stepUp(activity: FragmentActivity, scope: String, title: String, subtitle: String): StepUpGrant {
        val desktop = pairingStore.current() ?: throw NotConnectedException()
        val alias = desktop.stepUpAlias ?: throw IllegalStateException("no step-up key")
        val api = connection.apiOrNull() ?: throw NotConnectedException()
        val challenge = api.stepUpChallenge(scope)
        val payload = StepUpPayload.build(desktop.deviceId, challenge.challengeId, challenge.nonce, scope)
        val signature = BiometricSigner(keys).sign(activity, alias, payload, title, subtitle)
        return api.stepUpVerify(challenge.challengeId, Base64.getEncoder().encodeToString(signature))
    }

    /** Step-up for terminal / files_write / config_write, performed by the activity host. */
    val stepUpGate = io.github.tianli0.switchtether.domain.StepUpGate({ activity, scope ->
        stepUp(activity, scope, context.getString(R.string.stepup_prompt_title), context.getString(R.string.stepup_prompt_subtitle))
    })

    @Volatile private var stepUpEnabledCache = true

    init {
        appScope.launch { pairingStore.desktop.collect { stepUpEnabledCache = it?.stepUpEnabled == true } }
        notifier.start()
        liveUpdates.start()
        widget.start()
        money.start()
        // Pinning a task while the app is open must keep the connection once the app goes to the
        // background (foreground services may only be started from the foreground).
        appScope.launch {
            liveUpdates.pins.collect { pins ->
                if (pins.isNotEmpty() && isForeground && pairingStore.current() != null) {
                    io.github.tianli0.switchtether.notify.MonitorService.start(context)
                } else if (pins.isEmpty() && serviceRunning && !settingsStore.backgroundConnection.first()) {
                    io.github.tianli0.switchtether.notify.MonitorService.stop(context)
                }
            }
        }
    }

    /** Whether the current pairing has a step-up key (terminal/file writes possible at all). */
    fun pairingStoreStepUpEnabled(): Boolean = stepUpEnabledCache

    val deviceName: String get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").ifEmpty { "Android" }
}
