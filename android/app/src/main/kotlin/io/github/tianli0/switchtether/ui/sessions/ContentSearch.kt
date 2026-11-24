package io.github.tianli0.switchtether.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.protocol.SessionSearch
import io.github.tianli0.switchtether.remote.protocol.SessionSearchResult
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.relativeTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the "search conversation content" part of the Sessions page shows. ANDROID_UI.md §25.2. */
data class ContentSearchUi(
    val enabled: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SessionSearchResult>? = null,
    val partial: Boolean = false,
    val unsupported: Boolean = false,
    val error: String? = null,
)

/**
 * Searches session contents on the computer (`GET /v1/sessions/search`) as the user types:
 * debounced, and a newer query cancels the request of an older one, so a slow answer never
 * replaces the results of what is typed now.
 */
class ContentSearch(
    private val scope: CoroutineScope,
    private val search: suspend (query: String, agent: String?) -> SessionSearch,
    private val debounceMs: Long = 400,
) {
    private val _state = MutableStateFlow(ContentSearchUi())
    val state: StateFlow<ContentSearchUi> = _state.asStateFlow()
    private var job: Job? = null
    private var agent: String? = null

    fun setEnabled(on: Boolean) {
        _state.value = _state.value.copy(enabled = on)
        if (on) run(_state.value.query) else cancel()
    }

    fun setQuery(query: String, agent: String?) {
        val changed = query != _state.value.query || agent != this.agent
        this.agent = agent
        _state.value = _state.value.copy(query = query)
        if (changed && _state.value.enabled) run(query)
    }

    private fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(searching = false)
    }

    private fun run(query: String) {
        job?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _state.value = _state.value.copy(searching = false, results = null, partial = false, error = null, unsupported = false)
            return
        }
        _state.value = _state.value.copy(searching = true, error = null)
        val forAgent = agent
        job = scope.launch {
            delay(debounceMs)
            try {
                val r = search(q, forAgent)
                _state.value = _state.value.copy(searching = false, results = r.results, partial = r.partial, unsupported = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // An older computer has no such endpoint ("search" is taken as a session id there).
                val old = e.httpStatus == 404
                _state.value = _state.value.copy(searching = false, results = null, unsupported = old, error = if (old) null else e.body.message.ifEmpty { e.code })
            } catch (e: Exception) {
                _state.value = _state.value.copy(searching = false, results = null, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

/** [snippet] with every case-insensitive occurrence of [query] in bold. */
fun highlightMatches(snippet: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    append(snippet)
    if (q.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val i = snippet.indexOf(q, from, ignoreCase = true)
        if (i < 0) break
        addStyle(style, i, i + q.length)
        from = i + q.length
    }
}

/** The content results under the search bar (replaces the session list while the mode is on). */
@Composable
fun ContentSearchResults(ui: ContentSearchUi, onOpen: (SessionSearchResult) -> Unit, modifier: Modifier = Modifier) {
    val muted = COUITheme.colorScheme.onSurfaceVariantSummary
    Column(modifier.testTag("content-search")) {
        val note = when {
            ui.unsupported -> stringResource(R.string.search_content_unsupported)
            ui.error != null -> stringResource(R.string.search_content_failed, ui.error)
            ui.searching && ui.results == null -> stringResource(R.string.search_content_searching)
            ui.results != null && ui.results.isEmpty() && !ui.searching -> stringResource(R.string.search_content_none, ui.query.trim())
            ui.query.isBlank() -> stringResource(R.string.search_content_hint)
            else -> null
        }
        note?.let {
            Text(it, style = COUITheme.textStyles.footnote1, color = muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
        }
        val results = ui.results.orEmpty()
        if (results.isNotEmpty()) {
            val accent = SpanStyle(fontWeight = FontWeight.Bold, color = COUITheme.colorScheme.primary)
            GroupCard {
                results.forEachIndexed { i, r ->
                    val meta = listOfNotNull(
                        Formatters.agentLabel(r.agent),
                        r.cwd?.let { c -> c.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') }?.takeIf { it.isNotBlank() },
                        r.timeMs?.let { relativeTime(it) },
                        if (r.matchCount > 1) stringResource(R.string.search_content_matches, r.matchCount) else null,
                    ).joinToString(" · ")
                    BasicComponent(
                        title = r.title?.takeIf { it.isNotBlank() } ?: Formatters.agentLabel(r.agent),
                        summary = meta,
                        bottomAction = {
                            Text(
                                highlightMatches(r.snippet, ui.query, accent),
                                style = COUITheme.textStyles.footnote1,
                                color = muted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        },
                        cardListPosition = cardListPositionOf(i, results.size),
                        onClick = { onOpen(r) },
                        modifier = Modifier.testTag("content-result"),
                    )
                }
            }
            if (ui.partial) {
                Text(stringResource(R.string.search_content_partial), style = COUITheme.textStyles.footnote1, color = muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }
}
