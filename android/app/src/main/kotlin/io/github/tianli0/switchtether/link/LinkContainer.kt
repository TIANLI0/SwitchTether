package io.github.tianli0.switchtether.link

import android.content.Context
import android.os.PowerManager
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.CompanionLink
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.connection.KeepAlive
import io.github.tianli0.switchtether.connection.KeystoreRelaySecrets
import io.github.tianli0.switchtether.connection.LocalNetworkAccess
import io.github.tianli0.switchtether.connection.NetworkMonitor
import io.github.tianli0.switchtether.connection.NsdDiscovery
import io.github.tianli0.switchtether.connection.ReconnectWorker
import io.github.tianli0.switchtether.data.DataStoreStores
import io.github.tianli0.switchtether.data.LinkPrefs
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.data.SettingsStore
import io.github.tianli0.switchtether.diag.ExitDiagnostics
import io.github.tianli0.switchtether.diag.ProcessSummary
import io.github.tianli0.switchtether.domain.RemoteSessionRepository
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.forward.PortForwards
import io.github.tianli0.switchtether.notify.AndroidLiveUpdateChannel
import io.github.tianli0.switchtether.notify.ConversationNotifier
import io.github.tianli0.switchtether.notify.LiveUpdates
import io.github.tianli0.switchtether.notify.MonitorService
import io.github.tianli0.switchtether.notify.Notifier
import io.github.tianli0.switchtether.remote.forward.LocalForwarder
import io.github.tianli0.switchtether.remote.live.LivePhase
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.security.KeystoreKeys
import io.github.tianli0.switchtether.widget.StatusWidgetUpdater
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Everything of the `:link` process (docs/ANDROID_UI.md §38): the background connection to the
 * paired computer, the notifications and Live Update cards derived from it, the home-screen widget,
 * port forwards, and the monitor service that keeps the process alive.
 *
 * The UI process has its own connection while the app is in use, and binds [LinkBridgeService]
 * meanwhile. Once the app is left, the UI process holds nothing and the system may reclaim it; only
 * this small process stays — and only when something needs it (the "Stay connected in background"
 * setting, a pinned task, a port forward), otherwise it ends its connection too.
 */
class LinkContainer(private val context: Context) : LinkHost {
    override val appScope = CoroutineScope(SupervisorJob())
    val keys = KeystoreKeys(context)
    private val stores = DataStoreStores(context)
    val pairingStore: PairingStore = stores
    val settingsStore: SettingsStore = stores
    val linkPrefs: LinkPrefs = stores

    private val baseClient = OkHttpClient()
    private val localNetwork = LocalNetworkAccess(context)
    private val discovery = NsdDiscovery(context)

    val connection = ConnectionManager(
        scope = appScope,
        pairingStore = pairingStore,
        identityLoader = { keys.loadIdentity(it) },
        onRevoked = { desktop, _ -> revoked(desktop) },
        baseClient = baseClient,
        discover = { fp, timeoutMs -> discovery.find(fp, timeoutMs) },
        localNetworkAllowed = { localNetwork.isGranted() },
        relaySecrets = KeystoreRelaySecrets,
    )

    /** No offline snapshot here: that belongs to the UI process (and is written only by it). */
    val sessions: SessionRepository = RemoteSessionRepository(connection)

    private val network = NetworkMonitor(context) { connection.onNetworkAvailable() }

    /** The UI process is bound (the app is in use, or was left less than the grace period ago). */
    @Volatile private var uiBound = false

    /** An activity of the app is started (notifications stay quiet; foreground services may start). */
    @Volatile var uiForeground = false
        private set

    @Volatile private var serviceRunning = false

    /** Notification buttons and recovery probes that need the connection for a moment. */
    private val transientUsers = AtomicInteger(0)

    val notifyPrefsState = settingsStore.notifyPrefs.stateIn(appScope, SharingStarted.Eagerly, NotifyPrefs())
    private val autoPin = linkPrefs.autoPinPhoneSessions.stateIn(appScope, SharingStarted.Eagerly, true)

    /** Conversation notifications: session shortcuts, inline-reply threads (docs/ANDROID_UI.md §34). */
    val conversations = ConversationNotifier(context)
    private val notifier = Notifier(context, appScope, connection, notifyPrefsState, { uiForeground }, conversations) { id -> liveUpdates.covers(id) }

    /** Working/waiting live sessions (keeps the monitor service's CPU wake lock). */
    val activeSessions get() = notifier.activeSessions

    /** Home-screen widget data (memory only). */
    val widget = StatusWidgetUpdater(context, appScope, connection, sessions)

    /** Pinned tasks → Android 16 Live Updates (ColorOS 16 Fluid Cloud / 流体云). */
    val liveUpdates: LiveUpdates by lazy {
        LiveUpdates(
            context = context,
            scope = appScope,
            conn = connection,
            pairingStore = pairingStore,
            channel = AndroidLiveUpdateChannel(context),
            resolveApproval = { s, r, d, c -> sessions.resolveApproval(s, r, d, c) },
            enabled = { NotifyType.LIVE_UPDATE in notifyPrefsState.value.enabled },
            // The card replaces the ordinary "finished / failed / stopped" notification, so it sounds
            // exactly when that one would have (type on, agent / project not muted, not quiet hours,
            // app not in front).
            alertOnFinish = { s ->
                val prefs = notifyPrefsState.value
                val type = when (s.phase) {
                    LivePhase.FAILED -> NotifyType.TASK_FAILED
                    LivePhase.INTERRUPTED -> NotifyType.SESSION_INTERRUPTED
                    else -> NotifyType.TASK_COMPLETED
                }
                val minute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                !uiForeground && type in prefs.enabled && s.agent !in prefs.mutedAgents &&
                    (s.project == null || s.project !in prefs.mutedProjects) && !prefs.inQuietHours(minute)
            },
            autoPin = { autoPin.value },
        )
    }

    /** Port forwards to the computer's loopback (docs/PROTOCOL.md §7.11); memory only. */
    val portForwards: PortForwards by lazy { PortForwards(appScope, connection, ::forwardErrorPage) }

    /** Optional Bluetooth association with the computer (battery exemption, presence wake-ups). */
    val companion = CompanionLink(context, linkPrefs, appScope)

    private val keepAlive by lazy { KeepAlive(context, appScope, connection.state) }
    private val diagnostics = ExitDiagnostics(context)

    /** The UI process's side of the conversation (see [LinkBridgeService]). */
    val bridge = LinkBridge(this)

    init {
        appScope.launch { runCatching { stores.migrateLegacy(context) } }
        notifier.start()
        appScope.launch(Dispatchers.IO) { conversations.prune() }
        liveUpdates.start()
        widget.start()
        appScope.launch {
            combine(liveUpdates.pins, portForwards.active) { pins, forwards -> LinkState(pins.sorted(), forwards) }
                .distinctUntilChanged()
                .collect { bridge.pushState(it) }
        }
        appScope.launch { portForwards.ended.collect { bridge.forwardEnded(it) } }
        appScope.launch {
            combine(connection.state.map { it is ConnectionState.Online }, liveUpdates.pins) { online, _ -> online }
                .distinctUntilChanged()
                .collect { publishProcessState() }
        }
        // Pinning a task or starting a forward while the app is open must keep the connection once
        // the app goes to the background (foreground services may only be started from the foreground).
        appScope.launch {
            combine(liveUpdates.pins, portForwards.active) { pins, forwards -> pins.isNotEmpty() || forwards.isNotEmpty() }
                .distinctUntilChanged()
                .collect { keep ->
                    if (keep && uiForeground && pairingStore.current() != null) {
                        MonitorService.start(context)
                    } else if (!keep && serviceRunning && !settingsStore.backgroundConnection.first()) {
                        MonitorService.stop(context)
                    }
                }
        }
        // Settings → "Stay connected in background" (written by the UI process).
        appScope.launch {
            settingsStore.backgroundConnection.distinctUntilChanged().drop(1).collect { enabled -> onBackgroundSetting(enabled) }
        }
    }

    private fun publishProcessState() {
        diagnostics.setSummary(
            ProcessSummary(
                foreground = uiForeground,
                monitor = serviceRunning,
                online = connection.state.value is ConnectionState.Online,
                transfers = 0,
            ),
        )
    }

    /** Runs the connection while anything here needs it, and only then. */
    private fun updateConnection() {
        if (uiBound || serviceRunning || transientUsers.get() > 0) {
            network.start()
            connection.start()
        } else {
            network.stop()
            connection.stop()
            // Idle sockets and TLS buffers are re-created on demand.
            runCatching { baseClient.connectionPool.evictAll() }
        }
    }

    /** Pinned tasks and port forwards need the connection wherever the computer is. */
    private fun hasHardKeepAlive(): Boolean =
        liveUpdates.pins.value.isNotEmpty() || portForwards.active.value.isNotEmpty()

    /**
     * Whether the monitor service should run in the background right now: a pinned task or port
     * forward, or the "Stay connected in background" setting — the latter only while the bound
     * computer is nearby when the user chose "nearby only" (see [CompanionLink]).
     */
    suspend fun wantsMonitor(): Boolean {
        if (pairingStore.current() == null) return false
        if (hasHardKeepAlive()) return true
        return settingsStore.backgroundConnection.first() && companion.allowsBackgroundKeepAlive()
    }

    // ---- UI process (LinkBridge) ----

    fun onUiBound() {
        uiBound = true
        updateConnection()
    }

    fun onUiUnbound() {
        uiBound = false
        uiForeground = false
        publishProcessState()
        updateConnection()
    }

    override fun onUiForeground(foreground: Boolean) {
        uiForeground = foreground
        publishProcessState()
        appScope.launch {
            if (foreground) {
                // Foreground services may be started now (the app's activity is visible).
                if (wantsMonitor()) {
                    ReconnectWorker.schedulePeriodic(context)
                    MonitorService.start(context)
                }
            } else if (serviceRunning && !wantsMonitor()) {
                MonitorService.stop(context)
            }
        }
    }

    /** The pairing was replaced or removed in the UI process: start over with what is stored now. */
    override fun onReset() {
        appScope.launch {
            connection.reset()
            if (pairingStore.current() == null) {
                portForwards.stopAll()
                conversations.forgetAll()
                ReconnectWorker.cancel(context)
                MonitorService.stop(context)
            }
            updateConnection()
            // Just paired with the app in front: the background connection starts now, not at the
            // next return to the app.
            if (uiForeground && wantsMonitor()) {
                ReconnectWorker.schedulePeriodic(context)
                MonitorService.start(context)
            }
        }
    }

    private suspend fun onBackgroundSetting(enabled: Boolean) {
        if (enabled && pairingStore.current() != null) {
            ReconnectWorker.schedulePeriodic(context)
            if (uiForeground && wantsMonitor()) MonitorService.start(context)
        }
        if (!enabled) {
            if (liveUpdates.pins.value.isEmpty()) ReconnectWorker.cancel(context)
            // Running port forwards keep the connection (a browser on the phone depends on it).
            if (portForwards.active.value.isEmpty() && liveUpdates.pins.value.isEmpty()) MonitorService.stop(context)
        }
    }

    // ---- Companion presence ----

    /** From [io.github.tianli0.switchtether.connection.CompanionPresenceService], or the settings page. */
    override fun onCompanionPresence(present: Boolean?) {
        appScope.launch {
            if (present != null) companion.setPresent(present)
            if (wantsMonitor()) {
                ReconnectWorker.schedulePeriodic(context)
                MonitorService.start(context)
            } else if (!uiForeground && serviceRunning) {
                // The computer left: let the process go instead of holding a connection nobody nearby needs.
                MonitorService.stop(context)
            }
        }
    }

    // ---- Monitor service ----

    fun onServiceKeepAlive() {
        serviceRunning = true
        publishProcessState()
        updateConnection()
        keepAlive.start()
    }

    /** The keep-alive alarm fired (see [KeepAlive]). */
    fun keepAliveTick() {
        if (serviceRunning) keepAlive.tick(connection) else KeepAlive.cancel(context)
    }

    fun onServiceStopped() {
        serviceRunning = false
        publishProcessState()
        keepAlive.stop()
        updateConnection()
    }

    // ---- Notification buttons ----

    /**
     * For a notification button (approve, answer, reply) pressed while nothing keeps the connection:
     * starts it for the duration of the action. Every call is paired with [releaseBackgroundConnection].
     */
    suspend fun awaitOnline(timeoutMs: Long): Boolean {
        transientUsers.incrementAndGet()
        updateConnection()
        if (connection.apiOrNull() != null) return true
        return withTimeoutOrNull(timeoutMs) { connection.state.first { it is ConnectionState.Online } } != null
    }

    fun releaseBackgroundConnection() {
        if (transientUsers.decrementAndGet() < 0) transientUsers.set(0)
        updateConnection()
    }

    /** The phone itself interrupted/stopped a session or killed a terminal (suppresses the echo notification). */
    override fun noteLocalAction(target: String, action: io.github.tianli0.switchtether.remote.notify.LocalAction) = notifier.noteLocalAction(target, action)

    override fun pin(summary: io.github.tianli0.switchtether.remote.protocol.SessionSummary, manual: Boolean): Boolean = liveUpdates.pin(summary, manual)
    override fun unpin(sessionId: String) = liveUpdates.unpin(sessionId)
    override fun onSessionOpened(sessionId: String) = liveUpdates.onSessionOpened(sessionId)
    override suspend fun attachForward(lease: io.github.tianli0.switchtether.remote.protocol.ForwardView, localPort: Int, process: String?, listening: Boolean?) =
        portForwards.attach(lease, localPort, process, listening)
    override fun updateForwardLease(lease: io.github.tianli0.switchtether.remote.protocol.ForwardView) = portForwards.updateLease(lease)
    override fun stopForward(leaseId: String) = portForwards.stop(leaseId)

    /** An approval was answered from its notification: withdraw it, or show the session's next open request. */
    fun requestAnswered(sessionId: String, requestId: String) = notifier.requestAnswered(sessionId, requestId)

    // ---- Recovery (WorkManager in the UI process, boot) ----

    /**
     * Periodic / boot recovery: starts the monitor service when the system allows it from the
     * background, otherwise checks once over pinned mTLS so pending approvals / questions / finished
     * tasks are still notified. Respects Android 12+ foreground-service start restrictions.
     */
    suspend fun recover() {
        if (pairingStore.current() == null) {
            ReconnectWorker.cancel(context)
            return
        }
        val bg = settingsStore.backgroundConnection.first()
        if (!bg && liveUpdates.pins.value.isEmpty()) return
        val canStartFgsFromBg =
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        // With a bound computer that is away ("nearby only"), a bounded probe is enough: the
        // presence service starts the monitor again when the computer comes back.
        if (canStartFgsFromBg && wantsMonitor()) MonitorService.start(context)
        if (connection.state.value is ConnectionState.Online) return
        transientUsers.incrementAndGet()
        try {
            updateConnection()
            val online = withTimeoutOrNull(PROBE_MS) { connection.state.first { it is ConnectionState.Online } } != null
            // The replay of what happened meanwhile arrives right after connecting.
            if (online) delay(SETTLE_MS)
        } finally {
            releaseBackgroundConnection()
        }
    }

    /** After boot / app update: schedule the recovery work when anything wants the connection. */
    suspend fun afterBoot() {
        if (pairingStore.current() == null) return
        if (settingsStore.backgroundConnection.first() || liveUpdates.pins.value.isNotEmpty()) {
            ReconnectWorker.scheduleImmediate(context)
            ReconnectWorker.schedulePeriodic(context)
        }
    }

    // ---- Revocation ----

    /**
     * The desktop revoked this phone (seen by this process's connection). Ends everything here and
     * removes the pairing; the UI process clears its own data now if it runs, or at its next start
     * ([LinkPrefs.pendingWipe]).
     */
    private suspend fun revoked(desktop: PairedDesktop) {
        ReconnectWorker.cancel(context)
        MonitorService.stop(context)
        portForwards.stopAll()
        linkPrefs.setPendingWipe(desktop.serverId)
        pairingStore.clear()
        keys.delete(desktop.identityAlias)
        keys.delete(desktop.stepUpAlias)
        keys.deleteAllExcept(emptySet())
        KeystoreRelaySecrets.destroy()
        conversations.forgetAll()
        bridge.revoked(desktop.serverId)
    }

    /** Page a browser on the phone gets when the forwarded port cannot be reached; reloads itself every 3 s. */
    private fun forwardErrorPage(kind: LocalForwarder.ErrorKind, port: Int): String {
        val refused = kind == LocalForwarder.ErrorKind.REFUSED
        val title = if (refused) context.getString(R.string.forward_page_refused_title, port) else context.getString(R.string.forward_page_unreachable_title)
        val body = context.getString(if (refused) R.string.forward_page_refused_body else R.string.forward_page_unreachable_body)
        val footer = context.getString(R.string.forward_page_footer, port)
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=\"refresh\" content=\"3\"><title>SwitchTether</title><style>" +
            "body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f4f5f7;color:#1f2328}" +
            "@media(prefers-color-scheme:dark){body{background:#121417;color:#e6e8eb}}main{max-width:28rem;padding:2rem}" +
            "h1{font-size:1.2rem;margin:0 0 .75rem}p{margin:.3rem 0;line-height:1.55;opacity:.8}small{opacity:.55}" +
            "</style></head><body><main><h1>${esc(title)}</h1><p>${esc(body)}</p><p><small>${esc(footer)}</small></p></main></body></html>"
    }

    /** Releases re-creatable memory. */
    fun onTrimMemory() {
        runCatching { baseClient.connectionPool.evictAll() }
    }

    private companion object {
        const val PROBE_MS = 15_000L
        const val SETTLE_MS = 8_000L
    }
}
