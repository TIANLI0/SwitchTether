package io.github.tianli0.switchtether.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.domain.PairingException
import io.github.tianli0.switchtether.domain.PairingRepository
import io.github.tianli0.switchtether.domain.PairingResult
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.pairing.PairingLinkException
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
    data class Pairing(val link: PairingLink) : PairingUiState
    data class Done(val result: PairingResult) : PairingUiState
    data class LinkInvalid(val reason: PairingLink.Reason, val field: String?) : PairingUiState
    data class Failed(val link: PairingLink, val reason: PairingException.Reason, val detail: String?) : PairingUiState
}

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

    fun pair() {
        val link = when (val s = _state.value) {
            is PairingUiState.Confirm -> s.link
            is PairingUiState.Failed -> s.link
            else -> return
        }
        _state.value = PairingUiState.Pairing(link)
        job = viewModelScope.launch {
            _state.value = try {
                val result = repository.pair(link, deviceName, osVersion, appVersion)
                _linkText.value = "" // the secret is single-use; never keep it around
                onSuccess(result)
                PairingUiState.Done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingException) {
                PairingUiState.Failed(link, e.reason, e.detail)
            } catch (e: Exception) {
                PairingUiState.Failed(link, PairingException.Reason.FAILED, e.message)
            }
        }
    }
}
