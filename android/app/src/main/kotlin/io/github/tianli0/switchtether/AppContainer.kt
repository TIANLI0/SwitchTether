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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Manual dependency container (no DI framework) of the UI process. The background side —
 * notifications, Live Update cards, the widget, port forwards, the monitor service — lives in the
 * `:link` process ([io.github.tianli0.switchtether.link.LinkContainer], docs/ANDROID_UI.md §38);
 * this process connects only while the app is in use and can be reclaimed once it is left.
 */
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

    /** Offline snapshot: sealed session list on disk, conversations in memory only (see SessionCache). */
    val sessionCache = io.github.tianli0.switchtether.data.SessionCache(
        java.io.File(context.filesDir, "session-cache"),
        io.github.tianli0.switchtether.security.KeystoreBlobCipher("switchtether_session_snapshot"),
    )

    val sessions: SessionRepository = RemoteSessionRepository(connection, sessionCache) { pairingStore.current()?.serverId }
    val management: ManagementRepository = RemoteManagementRepository(connection)

    val pairing: PairingRepository = DefaultPairingRepository(
        keys = keyProvider,
        store = pairingStore,
        baseClient = baseClient,
        relaySecrets = io.github.tianli0.switchtether.connection.KeystoreRelaySecrets,
        discover = { fp, timeoutMs -> if (localNetwork.isGranted()) discovery.find(fp, timeoutMs) else emptyList() },
        onReplaced = { previous ->
            // Best effort: the old desktop keeps a record until revoked there; wipe our keys for it.
            keys.delete(previous.identityAlias)
            keys.delete(previous.stepUpAlias)
            // Its offline snapshot never shows under the new computer.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sessionCache.forget(previous.serverId) }
        },
    )

    private val network = NetworkMonitor(context) { connection.onNetworkAvailable() }

    @Volatile var isForeground: Boolean = false
        private set

    /** Notification settings (the `:link` process posts the notifications and reads the same store). */
    val notifyPrefsState = settingsStore.notifyPrefs.stateIn(appScope, SharingStarted.Eagerly, io.github.tianli0.switchtether.remote.notify.NotifyPrefs())

    /** Shared with the `:link` process (companion, auto-pin, pending wipe). */
    val linkPrefs: io.github.tianli0.switchtether.data.LinkPrefs = stores

    /** Messages to / from the `:link` process. */
    val link = io.github.tianli0.switchtether.link.LinkClient(context)

    /** Pinned tasks; the cards are posted by the `:link` process. */
    val livePins = io.github.tianli0.switchtether.link.LivePins(context, appScope, link, linkPrefs)

    val money = io.github.tianli0.switchtether.domain.MoneySettings(context, appScope, connection)

    /** Session detail currently on screen (Android 17 Handoff offers it to the user's other device). */
    val handoffSession = MutableStateFlow<String?>(null)

    /** Paired desktop's server id, kept in memory for synchronous callers (Handoff). */
    @Volatile var pairedServerIdCache: String? = null
        private set

    /**
     * Large transfers (attachment uploads, uploads into a folder, downloads) as Android 14+ user-initiated
     * data transfer jobs with a progress notification (docs/ANDROID_UI.md §36).
     */
    val transferJobs = io.github.tianli0.switchtether.transfer.JobTransferEnvelope(context) { isForeground }
    val transfers = io.github.tianli0.switchtether.transfer.TransferManager(
        io.github.tianli0.switchtether.transfer.FileTransferJournal(java.io.File(context.filesDir, "transfers/journal.json")),
        transferJobs,
    )
    /** Read synchronously before any transfer can start, so the journal of a dead process is never overwritten. */
    private val transferRecovery = transfers.recover()

    /** Foreground time not yet counted by the computer (PROTOCOL.md §7.15). */
    val foregroundTime = io.github.tianli0.switchtether.data.ForegroundTime(java.io.File(context.filesDir, "stats/foreground.json"), appScope)
    private var foregroundTicker: kotlinx.coroutines.Job? = null

    /** Why earlier app processes ended (About → 诊断记录, docs/ANDROID_UI.md §37). */
    val diagnostics = io.github.tianli0.switchtether.diag.ExitDiagnostics(context)

    /** Preview cache + explicit downloads of remote files (docs/ANDROID_UI.md "Downloads vs cache"). */
    val fileStore = io.github.tianli0.switchtether.files.FileStore(context, serverId = { pairedServerIdCache }, api = { connection.apiOrNull() }, transfers = transfers)

    init {
        appScope.launch { pairingStore.desktop.collect { pairedServerIdCache = it?.serverId } }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { diagnostics.refresh() }
        appScope.launch {
            kotlinx.coroutines.flow.combine(connection.state, transfers.active) { s, t -> (s is io.github.tianli0.switchtether.connection.ConnectionState.Online) to t.size }
                .distinctUntilChanged()
                .collect { publishProcessState() }
        }
        cleanUpInterruptedTransfers()
        appScope.launch {
            connection.state.map { it is io.github.tianli0.switchtether.connection.ConnectionState.Online }
                .distinctUntilChanged()
                .collect { online -> if (online) reportForegroundTime() }
        }
    }

    /** Hands the foreground time to a computer that counts it (feature `remote_stats`). */
    private suspend fun reportForegroundTime() {
        val api = connection.apiOrNull() ?: return
        val supported = runCatching { "remote_stats" in api.features() }.getOrDefault(false)
        if (supported) foregroundTime.flush(api)
    }

    /** Hands the system a summary of this process (foreground, connected, transfers) for its exit record. */
    private fun publishProcessState() {
        diagnostics.setSummary(
            io.github.tianli0.switchtether.diag.ProcessSummary(
                foreground = isForeground,
                // The monitor service runs in the `:link` process, which records its own state.
                monitor = false,
                online = connection.state.value is io.github.tianli0.switchtether.connection.ConnectionState.Online,
                transfers = transfers.active.value.size,
            ),
        )
    }

    /**
     * Transfers the previous process was running when it ended: reported once, never resumed (so
     * nothing is sent twice). A pending download entry is removed at once; an unfinished upload or
     * attachment on the computer is discarded at the next connection (the computer would drop it after
     * 6 h anyway).
     */
    private fun cleanUpInterruptedTransfers() {
        val r = transferRecovery
        io.github.tianli0.switchtether.transfer.JobTransferEnvelope.postInterrupted(context, r.interrupted)
        if (r.cleanup.isEmpty()) return
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val remote = r.cleanup.filter { it.ref?.attachmentId != null || it.ref?.uploadId != null }
            for (e in r.cleanup) {
                e.ref?.pendingUri?.let { u -> runCatching { context.contentResolver.delete(android.net.Uri.parse(u), null, null) } }
                if (e !in remote) transfers.cleanedUp(e.id)
            }
            if (remote.isEmpty()) return@launch
            connection.state.first { it is io.github.tianli0.switchtether.connection.ConnectionState.Online }
            val api = connection.apiOrNull() ?: return@launch
            for (e in remote) {
                val ref = e.ref ?: continue
                runCatching {
                    if (ref.attachmentId != null && ref.sessionId != null) api.cancelAttachment(ref.sessionId, ref.attachmentId)
                    else if (ref.uploadId != null) api.cancelUpload(ref.uploadId)
                }
                transfers.cleanedUp(e.id)
            }
        }
    }

    /** Self-update (GitHub releases; checksum + signing-certificate verified). */
    val updates = io.github.tianli0.switchtether.update.UpdateManager(context)

    /** Set by the "new version" notification: the main shell shows the update sheet. */
    val showUpdateRequest = MutableStateFlow(false)

    /** The phone itself interrupted/stopped a session or killed a terminal (suppresses the echo notification). */
    fun noteLocalAction(target: String, action: io.github.tianli0.switchtether.remote.notify.LocalAction) {
        link.send(
            io.github.tianli0.switchtether.link.LinkProtocol.message(io.github.tianli0.switchtether.link.LinkProtocol.LOCAL_ACTION) {
                putString(io.github.tianli0.switchtether.link.LinkProtocol.K_TARGET, target)
                putString(io.github.tianli0.switchtether.link.LinkProtocol.K_ACTION, action.name)
            },
        )
    }

    /** The new-session sheet was left for the folder picker page; reopen it when the picker closes. */
    val newSessionReopen = MutableStateFlow(false)
    /** One-time hints the user has dismissed (e.g. the read-only session tip). */
    val hintPrefs: android.content.SharedPreferences by lazy { context.getSharedPreferences("ui_hints", Context.MODE_PRIVATE) }
    /** Terminal view preferences (font size only). */
    val terminalPrefs: android.content.SharedPreferences by lazy { context.getSharedPreferences("terminal", Context.MODE_PRIVATE) }
    /** Last launcher choices for a new managed session. */
    val newSessionPrefs: io.github.tianli0.switchtether.ui.sessions.NewSessionPrefs by lazy { io.github.tianli0.switchtether.ui.sessions.SharedNewSessionPrefs(context) }

    /** Session id requested by a notification tap (consumed by the main shell). */
    val openSessionRequest = MutableStateFlow<String?>(null)

    /** Terminal id to show in Workspace → Terminals (from a session mirrored from a terminal). */
    val openTerminalRequest = MutableStateFlow<String?>(null)

    /** Optional Bluetooth association with the computer (battery exemption, presence wake-ups). */
    val companion = io.github.tianli0.switchtether.connection.CompanionLink(context, linkPrefs, appScope)

    /** "Nearby only" changed or an association was added / removed: the `:link` process decides again. */
    fun onCompanionChanged() {
        link.send(io.github.tianli0.switchtether.link.LinkProtocol.message(io.github.tianli0.switchtether.link.LinkProtocol.COMPANION, -1))
    }

    /** Pending "stop the connection" after leaving the app. */
    private var backgroundStop: kotlinx.coroutines.Job? = null

    fun onForeground() {
        isForeground = true
        foregroundTime.onStart()
        foregroundTicker?.cancel()
        foregroundTicker = appScope.launch {
            var ticks = 0
            while (true) {
                kotlinx.coroutines.delay(FOREGROUND_CHECKPOINT_MS)
                foregroundTime.checkpoint()
                if (++ticks % FOREGROUND_REPORT_TICKS == 0) reportForegroundTime()
            }
        }
        publishProcessState()
        backgroundStop?.cancel()
        backgroundStop = null
        network.start()
        connection.start()
        // Binds the `:link` process: it connects too, and may start the monitor service now that
        // the app is in front (pinned tasks, port forwards, "Stay connected in background").
        link.setForeground(true)
    }

    fun onBackground() {
        isForeground = false
        foregroundTime.onStop()
        foregroundTicker?.cancel()
        foregroundTicker = null
        appScope.launch { reportForegroundTime() }
        publishProcessState()
        link.setForeground(false)
        // After a short grace, so a quick look at another app (copying a code, a glance at a
        // message) does not cost a full reconnect, which via the relay takes seconds. Then this
        // process holds nothing: the `:link` process keeps the background connection if wanted.
        backgroundStop?.cancel()
        backgroundStop = appScope.launch {
            kotlinx.coroutines.delay(BACKGROUND_GRACE_MS)
            if (!isForeground) {
                network.stop()
                connection.stop()
                onTrimMemory()
            }
        }
        link.unbindAfter(BACKGROUND_GRACE_MS)
    }

    /** Releases re-creatable memory (called from Application.onTrimMemory and after backgrounding). */
    fun onTrimMemory() {
        // Every API client is built from baseClient.newBuilder(), so they share this pool;
        // evictAll() closes idle connections only (the live WebSocket is in use and stays).
        runCatching { baseClient.connectionPool.evictAll() }
    }

    /** Settings toggle "Stay connected in background" (the `:link` process follows the stored value). */
    fun setBackgroundConnection(enabled: Boolean) {
        appScope.launch { settingsStore.setBackgroundConnection(enabled) }
    }

    /**
     * Set right after a successful `POST /v1/pair` so the onboarding flow can show the 6-digit
     * verification code before the main UI replaces it; cleared by [onPaired].
     */
    val pendingVerification = MutableStateFlow<io.github.tianli0.switchtether.domain.PairingResult?>(null)

    /** A pairing link delivered by a VIEW intent; consumed by the onboarding screen. */
    val incomingPairingLink = MutableStateFlow<String?>(null)

    /** "Share to SwitchTether": the pending share until the user picks where it goes (docs/ANDROID_UI.md §32). */
    val shareInbox = io.github.tianli0.switchtether.share.ShareInbox()

    /** After the user confirmed the verification code: connect to the new desktop. */
    fun onPaired() {
        pendingVerification.value = null
        connection.reset()
        connection.start()
        link.send(io.github.tianli0.switchtether.link.LinkProtocol.message(io.github.tianli0.switchtether.link.LinkProtocol.RESET))
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
        pairingStore.clear()
        keys.delete(desktop.identityAlias)
        keys.delete(desktop.stepUpAlias)
        keys.deleteAllExcept(emptySet())
        // The `:link` process stops the monitor service, its forwards and the conversations
        // (session shortcuts) of that computer.
        link.send(io.github.tianli0.switchtether.link.LinkProtocol.message(io.github.tianli0.switchtether.link.LinkProtocol.RESET))
        wipeUiData()
    }

    /** What this process keeps for a computer that is gone (also after the `:link` process saw the revocation). */
    private suspend fun wipeUiData() {
        draftStore.clearAll()
        money.clear()
        fileStore.onServerForgotten() // temporary copies of that computer's files; user downloads stay
        clearCaches()
        stepUpGate.clear()
        linkPrefs.setPendingWipe(null)
        foregroundTime.clear()
    }

    fun clearCaches() {
        _cacheGeneration.value += 1
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { sessionCache.clear() }
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

    /** Port forwards to the computer's loopback (docs/PROTOCOL.md §7.11); the listeners run in the `:link` process. */
    val portForwards: io.github.tianli0.switchtether.forward.Forwards by lazy {
        io.github.tianli0.switchtether.link.LinkedForwards(appScope, link, connection, stepUpGate) { stepUpEnabledCache }
    }

    init {
        appScope.launch { pairingStore.desktop.collect { stepUpEnabledCache = it?.stepUpEnabled == true } }
        appScope.launch { runCatching { stores.migrateLegacy(context) } }
        // The `:link` process saw the desktop revoke this phone (now, or while this process was gone).
        appScope.launch { link.revoked.collect { if (linkPrefs.pendingWipe.first() != null) wipeAfterRevocation() } }
        appScope.launch { if (linkPrefs.pendingWipe.first() != null) wipeAfterRevocation() }
    }

    private suspend fun wipeAfterRevocation() {
        connection.reset()
        wipeUiData()
    }

    /** Whether the current pairing has a step-up key (terminal/file writes possible at all). */
    fun pairingStoreStepUpEnabled(): Boolean = stepUpEnabledCache

    val deviceName: String get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").ifEmpty { "Android" }

    private companion object {
        /** Connection kept after leaving the app when nothing else keeps it (below the desktop's 60 s idle close). */
        const val BACKGROUND_GRACE_MS = 45_000L
        const val FOREGROUND_CHECKPOINT_MS = 60_000L

        /** Foreground time is reported every this many checkpoints while in front. */
        const val FOREGROUND_REPORT_TICKS = 10
    }
}
