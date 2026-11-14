package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.pairing.HostRace
import io.github.tianli0.switchtether.remote.pairing.NoReachableHostException
import io.github.tianli0.switchtether.remote.pairing.PairingLink
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
    suspend fun pair(link: PairingLink, deviceName: String, osVersion: String, appVersion: String): PairingResult
}

/**
 * Pairing (docs/PROTOCOL.md §3): fresh Keystore identity (+ step-up key when the lock screen is
 * secure), race all host candidates with pinned mTLS `GET /v1/hello`, then `POST /v1/pair`.
 * Only after success is the pairing persisted; on failure the new keys are deleted.
 */
class DefaultPairingRepository(
    private val keys: KeyProvider,
    private val store: PairingStore,
    private val baseClient: OkHttpClient = OkHttpClient(),
    /** Called with the previous pairing when a new one replaces it (best-effort remote unpair + key wipe). */
    private val onReplaced: suspend (PairedDesktop) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val relaySecrets: io.github.tianli0.switchtether.connection.RelaySecrets = io.github.tianli0.switchtether.connection.RelaySecrets.None,
) : PairingRepository {
    override suspend fun pair(link: PairingLink, deviceName: String, osVersion: String, appVersion: String): PairingResult {
        val identity = keys.createIdentity()
        val stepUpAlias = if (keys.isDeviceSecure()) keys.createStepUpKey() else null
        try {
            val client = TlsClientFactory.create(identity, link.fingerprint, base = baseClient)
            val endpoints = link.hosts.map { Endpoint(it, link.port) }
            val (endpoint, hello) = try {
                HostRace.first(endpoints) { ep -> RemoteApi(client, ep).hello() }
            } catch (e: NoReachableHostException) {
                if (e.pinMismatch) throw PairingException(PairingException.Reason.CERTIFICATE_MISMATCH, cause = e)
                throw PairingException(PairingException.Reason.UNREACHABLE, e.failures.joinToString { "${it.first}" }, e)
            }
            when (hello.compatibility()) {
                Hello.Compatibility.DESKTOP_TOO_OLD -> throw PairingException(PairingException.Reason.INCOMPATIBLE_DESKTOP_TOO_OLD, hello.serverVersion)
                Hello.Compatibility.APP_TOO_OLD -> throw PairingException(PairingException.Reason.INCOMPATIBLE_APP_TOO_OLD, hello.serverVersion)
                Hello.Compatibility.OK -> Unit
            }
            val response = try {
                RemoteApi(client, endpoint).pair(
                    PairRequest(
                        pairingId = link.pairingId,
                        secret = link.secret,
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
                (reported == null || !Fingerprints.constantTimeEquals(reported, Fingerprints.fromHex(link.fingerprint)))
            ) {
                throw PairingException(PairingException.Reason.SERVER_FINGERPRINT_MISMATCH)
            }
            val previous = store.current()
            val desktop = PairedDesktop(
                serverId = response.serverId.ifEmpty { hello.serverId },
                name = response.serverName.ifEmpty { hello.serverName.ifEmpty { link.desktopName } },
                fingerprint = link.fingerprint,
                hosts = link.hosts,
                lastGoodHost = endpoint.host,
                port = link.port,
                deviceId = response.deviceId,
                identityAlias = identity.alias,
                stepUpAlias = stepUpAlias.takeIf { response.stepUpEnabled },
                stepUpEnabled = response.stepUpEnabled,
                pairedAtMs = clock(),
                relayUrl = link.relayUrl,
                // Token + key are secrets: stored only Keystore-encrypted; no key → relay unusable.
                relaySecret = link.relayKey?.let { relaySecrets.seal(io.github.tianli0.switchtether.connection.RelaySecret(link.relayToken, it)) },
            )
            store.save(desktop)
            if (!response.stepUpEnabled && stepUpAlias != null) keys.delete(stepUpAlias)
            if (previous != null && previous.identityAlias != identity.alias) runCatching { onReplaced(previous) }
            return PairingResult(
                desktop = desktop,
                serverCode = response.verificationCode,
                localCode = Fingerprints.verificationCode(link.fingerprint, identity.fingerprint),
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
