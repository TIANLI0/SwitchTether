package io.github.tianli0.switchtether.ui.common

import androidx.compose.ui.unit.dp
import io.github.tianli0.coui17.patterns.LocalPageTopInset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.NotConnectedException
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.coui17.patterns.ErrorState
import io.github.tianli0.coui17.patterns.LoadingState
import kotlin.coroutines.cancellation.CancellationException

/** Async UI value with real loading / error / offline states (no fake data). */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T, val refreshing: Boolean = false) : Loadable<T>
    data class Failed(val error: UiError) : Loadable<Nothing>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}

/** Classified error for consistent messages. */
data class UiError(val kind: Kind, val detail: String?) {
    enum class Kind { OFFLINE, BACKEND_UNAVAILABLE, NOT_FOUND, FORBIDDEN, RATE_LIMITED, SERVER, NETWORK }

    companion object {
        fun from(t: Throwable): UiError = when (t) {
            is NotConnectedException -> UiError(Kind.OFFLINE, null)
            is ApiException -> UiError(
                when (t.code) {
                    ErrorCodes.BACKEND_UNAVAILABLE -> Kind.BACKEND_UNAVAILABLE
                    ErrorCodes.NOT_FOUND -> Kind.NOT_FOUND
                    ErrorCodes.FORBIDDEN, ErrorCodes.STEP_UP_REQUIRED, ErrorCodes.DEVICE_REVOKED, ErrorCodes.UNPAIRED -> Kind.FORBIDDEN
                    ErrorCodes.RATE_LIMITED -> Kind.RATE_LIMITED
                    else -> Kind.SERVER
                },
                "${t.code}: ${t.body.message}".trim(),
            )
            else -> UiError(Kind.NETWORK, t.message ?: t.javaClass.simpleName)
        }
    }
}

/** Runs [block], mapping failures to [Loadable.Failed] (cancellation propagates). */
suspend fun <T> loadable(block: suspend () -> T): Loadable<T> = try {
    Loadable.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Loadable.Failed(UiError.from(e))
}

@Composable
fun UiError.title(): String = when (kind) {
    UiError.Kind.OFFLINE -> stringResource(R.string.error_offline)
    UiError.Kind.BACKEND_UNAVAILABLE -> stringResource(R.string.error_backend_unavailable)
    UiError.Kind.NOT_FOUND -> stringResource(R.string.error_not_found)
    UiError.Kind.FORBIDDEN -> stringResource(R.string.error_forbidden)
    UiError.Kind.RATE_LIMITED -> stringResource(R.string.error_rate_limited)
    UiError.Kind.SERVER -> stringResource(R.string.error_server)
    UiError.Kind.NETWORK -> stringResource(R.string.error_network)
}

/** Standard rendering of a [Loadable]: loading spinner, error with retry, or [content]. */
@Composable
fun <T> LoadableContent(state: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        // On an immersive page the body starts under the title bar: keep the state below it.
        Loadable.Loading -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(top = LocalPageTopInset.current)) {
            LoadingState(stringResource(R.string.state_loading))
        }
        // Centred like the loading state (it sat at the top of an empty page before).
        is Loadable.Failed -> androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().padding(top = LocalPageTopInset.current, bottom = 48.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            ErrorState(
                title = state.error.title(),
                message = if (state.error.kind == UiError.Kind.BACKEND_UNAVAILABLE) stringResource(R.string.error_backend_unavailable_detail) else state.error.detail,
                retryLabel = stringResource(R.string.action_retry),
                onRetry = onRetry,
            )
        }
        is Loadable.Ready -> content(state.value)
    }
}
