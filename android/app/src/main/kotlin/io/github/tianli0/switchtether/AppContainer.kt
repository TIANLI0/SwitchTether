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

    val connection = ConnectionManager(
        scope = appScope,
        pairingStore = pairingStore,
        identityLoader = { keys.loadIdentity(it) },
        onRevoked = { desktop, _ -> wipeLocal(desktop) },
        baseClient = baseClient,
    )

    val sessions: SessionRepository = RemoteSessionRepository(connection)
    val management: ManagementRepository = RemoteManagementRepository(connection)

    val pairing: PairingRepository = DefaultPairingRepository(
        keys = keyProvider,
        store = pairingStore,
        baseClient = baseClient,
        onReplaced = { previous ->
            // Best effort: the old desktop keeps a record until revoked there; wipe our keys for it.
            keys.delete(previous.identityAlias)
            keys.delete(previous.stepUpAlias)
        },
    )

    private val network = NetworkMonitor(context) { connection.onNetworkAvailable() }

    fun onForeground() {
        network.start()
        connection.start()
    }

    fun onBackground() {
        // Phase B adds an opt-in foreground service; until then nothing runs in the background.
        network.stop()
        connection.stop()
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
        val remoteOk = runCatching { (connection.apiOrNull() ?: throw NotConnectedException()).unpairSelf() }.isSuccess
        connection.reset()
        wipeLocal(desktop)
        return remoteOk
    }

    private suspend fun wipeLocal(desktop: PairedDesktop) {
        pairingStore.clear()
        keys.delete(desktop.identityAlias)
        keys.delete(desktop.stepUpAlias)
        keys.deleteAllExcept(emptySet())
        draftStore.clearAll()
        clearCaches()
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

    val deviceName: String get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").ifEmpty { "Android" }
}
