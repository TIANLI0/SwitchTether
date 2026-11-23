package io.github.tianli0.switchtether.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.domain.PairingException
import io.github.tianli0.switchtether.domain.PairingRepository
import io.github.tianli0.switchtether.domain.PairingResult
import io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop
import io.github.tianli0.switchtether.remote.pairing.ManualAddress
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.pairing.PairingLinkException
import io.github.tianli0.switchtether.remote.pairing.PairingTarget
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Intro : PairingUiState
    data object Scanning : PairingUiState
    data class Confirm(val link: PairingLink) : PairingUiState

    /**
     * Pairing with the 6-digit code shown on the desktop: a computer picked from "附近的电脑"
     * ([host] and [fingerprint] from its advertisement) or typed by address ([manual]).
     */
    data class EnterCode(
        val desktopName: String?,
        val host: String?,
        val port: Int,
        val fingerprint: String?,
        val manual: Boolean,
        val error: CodeError? = null,
    ) : PairingUiState

    data class Pairing(val target: PairingTarget) : PairingUiState
    data class Done(val result: PairingResult) : PairingUiState
    data class LinkInvalid(val reason: PairingLink.Reason, val field: String?) : PairingUiState
    data class Failed(val target: PairingTarget, val reason: PairingException.Reason, val detail: String?) : PairingUiState
}

enum class CodeError { INVALID_ADDRESS, INVALID_CODE }

class PairingViewModel(
    private val repository: PairingRepository,
    private val deviceSecure: () -> Boolean,
    private val deviceName: String,
    private val osVersion: String,
    private val appVersion: String,
    private val onSuccess: (PairingResult) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Intro)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private val _linkText = MutableStateFlow("")
    val linkText: StateFlow<String> = _linkText.asStateFlow()

    /** Without a secure lock screen there is no step-up key: terminal/file writes stay unavailable. */
    val isDeviceSecure: Boolean get() = deviceSecure()

    private var job: Job? = null

    fun startScan() {
        _state.value = PairingUiState.Scanning
    }

    fun backToIntro() {
        job?.cancel()
        _state.value = PairingUiState.Intro
    }

    fun onLinkTextChange(text: String) {
        _linkText.value = text
    }

    fun submitLink(text: String = _linkText.value) {
        _state.value = try {
            PairingUiState.Confirm(PairingLink.parse(text))
        } catch (e: PairingLinkException) {
            PairingUiState.LinkInvalid(e.reason, e.field)
        }
    }

    /** QR result: anything that is not a pairing link is reported, scanning can be restarted. */
    fun onScanned(text: String) = submitLink(text)

    /** "附近的电脑": pair the advertised computer with its pairing code. */
    fun pickNearby(d: DiscoveredDesktop) {
        _state.value = PairingUiState.EnterCode(d.name, d.host, d.port, d.fingerprint, manual = false)
    }

    /** No QR code and not found on the LAN: type the computer's address, then its pairing code. */
    fun enterAddress() {
        _state.value = PairingUiState.EnterCode(null, null, PairingLink.DEFAULT_PORT, null, manual = true)
    }

    /** Code screen submitted; [address] is used only when the address was typed by the user. */
    /** Whether the last code attempt used a typed address (a retry must let the user correct it). */
    private var lastCodeManual = false

    fun submitCode(address: String, code: String) {
        val s = _state.value as? PairingUiState.EnterCode ?: return
        lastCodeManual = s.manual
        val (host, port) = if (s.manual) {
            val a = ManualAddress.parse(address) ?: run {
                _state.value = s.copy(error = CodeError.INVALID_ADDRESS)
                return
            }
            a.host to a.port
        } else {
            (s.host ?: return) to s.port
        }
        val normalized = PairingTarget.normalizeCode(code) ?: run {
            _state.value = s.copy(error = CodeError.INVALID_CODE)
            return
        }
        run(PairingTarget.Code(s.desktopName ?: host, listOf(host), port, s.fingerprint, normalized))
    }

    /** Hosts the next pairing attempt will contact (for the Android 17 local-network prompt). */
    fun pendingHosts(address: String = ""): List<String> = when (val s = _state.value) {
        is PairingUiState.Confirm -> s.link.hosts
        is PairingUiState.Failed -> s.target.hosts
        is PairingUiState.EnterCode -> listOfNotNull(if (s.manual) ManualAddress.parse(address)?.host else s.host)
        else -> emptyList()
    }

    /** Confirm screen / retry after a failure. A failed code attempt goes back to the code screen. */
    fun pair() {
        when (val s = _state.value) {
            is PairingUiState.Confirm -> run(PairingTarget.Link(s.link))
            is PairingUiState.Failed -> when (val t = s.target) {
                is PairingTarget.Link -> run(t)
                is PairingTarget.Code -> _state.value = PairingUiState.EnterCode(
                    desktopName = t.desktopName,
                    host = t.hosts.firstOrNull(),
                    port = t.port,
                    fingerprint = t.fingerprint,
                    // A typed address stays editable (and pre-filled) instead of being locked in.
                    manual = lastCodeManual,
                )
            }
            else -> Unit
        }
    }

    private fun run(target: PairingTarget) {
        _state.value = PairingUiState.Pairing(target)
        job = viewModelScope.launch {
            _state.value = try {
                val result = repository.pair(target, deviceName, osVersion, appVersion)
                _linkText.value = "" // the secret is single-use; never keep it around
                onSuccess(result)
                PairingUiState.Done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingException) {
                PairingUiState.Failed(target, e.reason, e.detail)
            } catch (e: Exception) {
                PairingUiState.Failed(target, PairingException.Reason.FAILED, e.message)
            }
        }
    }
}
