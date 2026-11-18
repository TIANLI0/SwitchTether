package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.pairing.HostRace
import io.github.tianli0.switchtether.remote.pairing.NoReachableHostException
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.pairing.PairingTarget
import io.github.tianli0.switchtether.remote.tls.RecordingServerTrustManager
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.Hello
import io.github.tianli0.switchtether.remote.protocol.PairRequest
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.remote.tls.TlsClientFactory
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient

/** Key operations needed by pairing (implemented by the Android Keystore in production). */
interface KeyProvider {
    fun isDeviceSecure(): Boolean
    fun createIdentity(): ClientIdentity
    fun loadIdentity(alias: String): ClientIdentity?
    /** Returns the alias, or null when the device has no secure lock screen. */
    fun createStepUpKey(): String?
    fun stepUpPublicKeySpki(alias: String): String?
    fun delete(alias: String?)
}

data class PairingResult(
    val desktop: PairedDesktop,
    /** Code reported by the desktop (shown on both screens). */
    val serverCode: String?,
    /** Code computed locally from both certificate fingerprints. */
    val localCode: String,
    val stepUpEnabled: Boolean,
) {
    /** False means the server's code differs from ours: do not trust this pairing. */
    val codesMatch: Boolean get() = serverCode == null || serverCode == localCode
}

/** Pairing failure with a stable reason for the UI. */
class PairingException(val reason: Reason, val detail: String? = null, cause: Throwable? = null) : Exception(reason.name + (detail?.let { ": $it" } ?: ""), cause) {
    enum class Reason { UNREACHABLE, CERTIFICATE_MISMATCH, EXPIRED, WRONG_SECRET, RATE_LIMITED, REVOKED_CERT, INCOMPATIBLE_DESKTOP_TOO_OLD, INCOMPATIBLE_APP_TOO_OLD, SERVER_FINGERPRINT_MISMATCH, FAILED }
}

interface PairingRepository {
    suspend fun pair(target: PairingTarget, deviceName: String, osVersion: String, appVersion: String): PairingResult

    suspend fun pair(link: PairingLink, deviceName: String, osVersion: String, appVersion: String): PairingResult =
        pair(PairingTarget.Link(link), deviceName, osVersion, appVersion)
}

/**
 * Pairing (docs/PROTOCOL.md §3): fresh Keystore identity (+ step-up key when the lock screen is
 * secure), race all host candidates with pinned mTLS `GET /v1/hello`, then `POST /v1/pair`.
 * Only after success is the pairing persisted; on failure the new keys are deleted.
 *
 * Addresses are not taken on trust from the QR code alone: when none of its hosts answers, the
 * desktop is looked up on the LAN by its certificate fingerprint ([discover]). A [PairingTarget.Code]
 * without a fingerprint (address typed by the user) records the certificate on first contact and
 * pins it from then on; the verification code shown afterwards is the check.
 */
class DefaultPairingRepository(
    private val keys: KeyProvider,
    private val store: PairingStore,
    private val baseClient: OkHttpClient = OkHttpClient(),
    /** Called with the previous pairing when a new one replaces it (best-effort remote unpair + key wipe). */
    private val onReplaced: suspend (PairedDesktop) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val relaySecrets: io.github.tianli0.switchtether.connection.RelaySecrets = io.github.tianli0.switchtether.connection.RelaySecrets.None,
    /** LAN lookup by certificate fingerprint (NSD); null = not available. */
    private val discover: (suspend (fingerprint: String, timeoutMs: Long) -> List<Endpoint>)? = null,
) : PairingRepository {
    override suspend fun pair(target: PairingTarget, deviceName: String, osVersion: String, appVersion: String): PairingResult {
        val identity = keys.createIdentity()
        val stepUpAlias = if (keys.isDeviceSecure()) keys.createStepUpKey() else null
        try {
            val endpoints = target.hosts.map { Endpoint(it, target.port) }
            // Certificate pin: from the QR code / LAN advertisement, or recorded on first contact.
            val fingerprint: String = target.fingerprint ?: try {
                HostRace.first(endpoints) { ep ->
                    val recorder = RecordingServerTrustManager()
                    RemoteApi(TlsClientFactory.createRecording(identity, recorder, base = baseClient), ep).hello()
                    recorder.fingerprint ?: error("no server certificate")
                }.second
            } catch (e: NoReachableHostException) {
                throw PairingException(PairingException.Reason.UNREACHABLE, e.failures.joinToString { "${it.first}" }, e)
            }
            val client = TlsClientFactory.create(identity, fingerprint, base = baseClient)
            val (endpoint, hello) = try {
                HostRace.first(endpoints) { ep -> RemoteApi(client, ep).hello() }
            } catch (e: NoReachableHostException) {
                if (e.pinMismatch) throw PairingException(PairingException.Reason.CERTIFICATE_MISMATCH, cause = e)
                // The computer may have a new address since the QR code was made: look for the
                // same certificate on the LAN before giving up.
                val found = discover?.let { find ->
                    runCatching { find(fingerprint, DISCOVERY_TIMEOUT_MS) }.getOrDefault(emptyList()).filter { it !in endpoints }
                }.orEmpty()
                val lan = if (found.isEmpty()) null else runCatching { HostRace.first(found) { ep -> RemoteApi(client, ep).hello() } }.getOrNull()
                lan ?: throw PairingException(PairingException.Reason.UNREACHABLE, e.failures.joinToString { "${it.first}" }, e)
            }
            when (hello.compatibility()) {
                Hello.Compatibility.DESKTOP_TOO_OLD -> throw PairingException(PairingException.Reason.INCOMPATIBLE_DESKTOP_TOO_OLD, hello.serverVersion)
                Hello.Compatibility.APP_TOO_OLD -> throw PairingException(PairingException.Reason.INCOMPATIBLE_APP_TOO_OLD, hello.serverVersion)
                Hello.Compatibility.OK -> Unit
            }
            val response = try {
                RemoteApi(client, endpoint).pair(
                    PairRequest(
                        pairingId = (target as? PairingTarget.Link)?.link?.pairingId,
                        secret = (target as? PairingTarget.Link)?.link?.secret,
                        code = (target as? PairingTarget.Code)?.code,
                        deviceName = deviceName.take(64),
                        osVersion = osVersion,
                        appVersion = appVersion,
                        stepUpPublicKey = stepUpAlias?.let(keys::stepUpPublicKeySpki),
                    ),
                )
            } catch (e: ApiException) {
                throw PairingException(
                    when (e.code) {
                        ErrorCodes.PAIRING_EXPIRED -> PairingException.Reason.EXPIRED
                        ErrorCodes.FORBIDDEN -> PairingException.Reason.WRONG_SECRET
                        ErrorCodes.RATE_LIMITED -> PairingException.Reason.RATE_LIMITED
                        ErrorCodes.DEVICE_REVOKED -> PairingException.Reason.REVOKED_CERT
                        else -> PairingException.Reason.FAILED
                    },
                    e.body.message,
                    e,
                )
            }
            // The fingerprint the server reports must be the one we pinned (defense in depth).
            val reported = runCatching { Fingerprints.fromHex(response.serverFingerprint.lowercase()) }.getOrNull()
            if (response.serverFingerprint.isNotEmpty() &&
                (reported == null || !Fingerprints.constantTimeEquals(reported, Fingerprints.fromHex(fingerprint)))
            ) {
                throw PairingException(PairingException.Reason.SERVER_FINGERPRINT_MISMATCH)
            }
            val previous = store.current()
            val desktop = PairedDesktop(
                serverId = response.serverId.ifEmpty { hello.serverId },
                name = response.serverName.ifEmpty { hello.serverName.ifEmpty { target.desktopName } },
                fingerprint = fingerprint,
                hosts = (listOf(endpoint.host) + target.hosts.filter { it != endpoint.host }).take(PairingLink.MAX_HOSTS),
                lastGoodHost = endpoint.host,
                port = endpoint.port,
                deviceId = response.deviceId,
                identityAlias = identity.alias,
                stepUpAlias = stepUpAlias.takeIf { response.stepUpEnabled },
                stepUpEnabled = response.stepUpEnabled,
                pairedAtMs = clock(),
                relayUrl = (target as? PairingTarget.Link)?.link?.relayUrl,
                // Token + key are secrets: stored only Keystore-encrypted; no key → relay unusable.
                // Code pairing has none; the relay settings arrive with GET /v1/relay on the first connect.
                relaySecret = (target as? PairingTarget.Link)?.link?.let { l -> l.relayKey?.let { relaySecrets.seal(io.github.tianli0.switchtether.connection.RelaySecret(l.relayToken, it)) } },
            )
            store.save(desktop)
            if (!response.stepUpEnabled && stepUpAlias != null) keys.delete(stepUpAlias)
            if (previous != null && previous.identityAlias != identity.alias) runCatching { onReplaced(previous) }
            return PairingResult(
                desktop = desktop,
                serverCode = response.verificationCode,
                localCode = Fingerprints.verificationCode(fingerprint, identity.fingerprint),
                stepUpEnabled = response.stepUpEnabled,
            )
        } catch (e: Throwable) {
            keys.delete(identity.alias)
            keys.delete(stepUpAlias)
            if (e is CancellationException || e is PairingException) throw e
            throw PairingException(PairingException.Reason.FAILED, e.message, e)
        }
    }
}

private const val DISCOVERY_TIMEOUT_MS = 4_000L
