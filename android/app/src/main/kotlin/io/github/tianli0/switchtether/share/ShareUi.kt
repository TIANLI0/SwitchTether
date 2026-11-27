package io.github.tianli0.switchtether.share

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowAlertDialog
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.NoticeBanner
import io.github.tianli0.coui17.patterns.PageDefaults
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.SheetButtons
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.home.RowIcon
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.sessions.MAX_ATTACHMENTS
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionRow
import io.github.tianli0.switchtether.ui.sessions.newSessionViewModel
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Above onboarding and the main UI: the "pair first" explanation, and keeping a waiting share's
 * files readable (persistable grant or a cache copy, [ShareFiles.secure]).
 */
@Composable
fun ShareRootEffects(paired: Boolean, verificationPending: Boolean) {
    val container = appContainer()
    val inbox = container.shareInbox
    val pending by inbox.state.collectAsState()
    val conn by container.connection.state.collectAsState()
    val p = pending ?: return
    val route = shareRoute(paired, verificationPending, conn is ConnectionState.Online)
    val context = LocalContext.current.applicationContext
    // Only what still needs it; the result replaces items by URI (a newer share is left alone).
    val toStage = p.content.items.filter { needsStaging(it, route) }
    LaunchedEffect(toStage.map { it.uri }) {
        if (toStage.isEmpty()) return@LaunchedEffect
        val done = withContext(Dispatchers.IO) { toStage.associate { it.uri to ShareFiles.secure(context, it) } }
        inbox.updateContent { c -> c.copy(items = c.items.map { done[it.uri] ?: it }) }
    }
    WindowAlertDialog(
        show = route == ShareRoute.ONBOARDING && !p.acknowledgedUnpaired,
        title = stringResource(R.string.share_unpaired_title),
        summary = stringResource(R.string.share_unpaired_body),
        confirmText = stringResource(R.string.share_unpaired_pair),
        cancelText = stringResource(R.string.share_cancel),
        onConfirm = inbox::acknowledgeUnpaired,
        onCancel = { cancelShare(context, inbox) },
    )
}

private fun cancelShare(context: android.content.Context, inbox: ShareInbox) {
    val content = inbox.state.value?.content
    inbox.cancel()
    if (content != null) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { ShareFiles.discard(context, content) }
}

/**
 * The target picker (main UI only): what was shared, then "new session" and the sessions that can
 * take a message. Offline it says so and keeps the share until the computer is back or the user
 * cancels.
 */
@Composable
fun ShareHost(nav: NavViewModel) {
    val container = appContainer()
    val inbox = container.shareInbox
    val pending by inbox.state.collectAsState()
    val conn by container.connection.state.collectAsState()
    val context = LocalContext.current
    val newSession = newSessionViewModel()
    /** The prompt this share put into the launcher (taken out again if another target is picked). */
    var promptSet by remember { mutableStateOf<String?>(null) }
    val p = pending
    val online = conn is ConnectionState.Online
    val direct = p?.stage as? ShareInbox.Stage.Direct
    // Direct Share waits offline in the same panel (it says the computer is offline).
    val show = p != null && (p.stage == ShareInbox.Stage.Picking || (direct != null && !online))
    var targets by remember { mutableStateOf<Loadable<List<SessionSummary>>>(Loadable.Loading) }
    val hasFiles = p?.content?.items?.isNotEmpty() == true
    // Direct Share (a session shortcut in the system share sheet): no picker when that session can
    // take the share; gone or not controllable → the picker.
    LaunchedEffect(direct, online) {
        if (direct == null || !online) return@LaunchedEffect
        val sessions = runCatching { container.sessions.list(SessionQuery(), limit = 100).items }.getOrNull()
        val id = directShareTarget(direct.sessionId, sessions.orEmpty(), hasFiles)
        inbox.resolveDirect(id)
        if (id != null) nav.push(Route.SessionDetail(id, sessions?.firstOrNull { it.id == id }?.title))
    }
    LaunchedEffect(show, online, hasFiles) {
        if (!show || !online || direct != null) return@LaunchedEffect
        targets = Loadable.Loading
        targets = runCatching { container.sessions.list(SessionQuery(), limit = 100).items }
            .fold({ Loadable.Ready(shareTargets(it, hasFiles)) }, { Loadable.Failed(io.github.tianli0.switchtether.ui.common.UiError.from(it)) })
    }
    PanelSheet(
        show = show,
        title = stringResource(R.string.share_title),
        onDismissRequest = { cancelShare(context.applicationContext, inbox) },
    ) {
        val content = p?.content ?: return@PanelSheet
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            SharedSummary(content)
            if (!online) {
                NoticeBanner(
                    text = stringResource(R.string.share_offline),
                    color = LocalDevColors.current.warn,
                    action = stringResource(R.string.action_retry),
                    onAction = { container.connection.retryNow() },
                    modifier = Modifier.testTag("share-offline"),
                )
            } else {
                GroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.new_session_title),
                        summary = stringResource(if (hasFiles) R.string.share_new_session_files_hint else R.string.share_new_session_hint),
                        startAction = { RowIcon(AppIcons.Add) },
                        cardListPosition = CardListPosition.Full,
                        modifier = Modifier.testTag("share-new-session"),
                        onClick = {
                            val text = content.text
                            if (!text.isNullOrBlank()) {
                                val merged = mergeShareText(newSession.state.value.prompt, text)
                                newSession.setPrompt(merged)
                                promptSet = merged
                            }
                            inbox.pickNewSession()
                            container.newSessionReopen.value = true
                        },
                    )
                }
                SmallTitle(stringResource(R.string.share_sessions))
                when (val t = targets) {
                    is Loadable.Ready -> if (t.value.isEmpty()) {
                        Hint(stringResource(R.string.share_no_sessions))
                    } else {
                        GroupCard {
                            t.value.forEachIndexed { i, s ->
                                SessionRow(
                                    session = s,
                                    position = cardListPositionOf(i, t.value.size),
                                    compact = true,
                                    modifier = Modifier.testTag("share-target:" + s.id),
                                    onClick = {
                                        // The launcher keeps no text this share put there.
                                        if (promptSet != null && newSession.state.value.prompt == promptSet) newSession.setPrompt("")
                                        promptSet = null
                                        inbox.pickSession(s.id)
                                        nav.push(Route.SessionDetail(s.id, s.title))
                                    },
                                )
                            }
                        }
                    }
                    is Loadable.Failed -> Hint(stringResource(R.string.share_sessions_failed))
                    else -> Hint(stringResource(R.string.state_loading))
                }
            }
            SheetButtons(
                primary = null,
                secondary = DialogButtonBarAction(text = stringResource(R.string.share_cancel), onClick = { cancelShare(context.applicationContext, inbox) }),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The new-session prompt after a share: same rule as a session's input box. */
private fun mergeShareText(current: String, shared: String) = mergeDraft(current, shared)

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = COUITheme.textStyles.body2,
        color = COUITheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageDefaults.TextGutter, vertical = 8.dp),
    )
}

/** What arrived: the text (a few lines), the files, and what will not be attached. */
@Composable
private fun SharedSummary(content: SharedContent) {
    val dev = LocalDevColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = PageDefaults.TextGutter).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content.text?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("share-text"))
        }
        if (content.items.isNotEmpty()) {
            Text(
                stringResource(R.string.share_files, content.items.size),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.testTag("share-files"),
            )
        }
    }
    val plan = planAttachments(content.items)
    if (plan.overflow.isNotEmpty()) {
        NoticeBanner(
            text = stringResource(R.string.share_too_many, content.items.size, MAX_ATTACHMENTS, plan.overflow.size),
            color = dev.warn,
            modifier = Modifier.testTag("share-too-many"),
        )
    }
    if (content.rejected > 0) {
        NoticeBanner(text = stringResource(R.string.share_rejected, content.rejected), color = dev.warn)
    }
}

/**
 * In a session page: when the pending share was sent here, its text goes into the input box and
 * its files into the attachment pipeline (uploaded at once, streamed). Nothing is sent; the user
 * reviews and presses send. Waits until the desktop says whether the session takes messages.
 */
@Composable
fun ShareIntoComposer(sessionId: String, vm: SessionDetailViewModel, state: SessionDetailState) {
    val container = appContainer()
    val inbox = container.shareInbox
    val pending by inbox.state.collectAsState()
    val ui = LocalContext.current
    val context = ui.applicationContext
    val scope = rememberCoroutineScope()
    val forHere = pending?.stage == ShareInbox.Stage.Deliver(sessionId)
    // The cached placeholder has no send capability yet: wait for the desktop's answer.
    val known = state.online && state.detail is Loadable.Ready && state.detail.valueOrNull?.capabilities?.sendMessage != null
    val takes = state.canSend
    val gone = state.online && (state.detail as? Loadable.Failed)?.error?.kind == io.github.tianli0.switchtether.ui.common.UiError.Kind.NOT_FOUND
    LaunchedEffect(forHere, gone) {
        // The session is gone (e.g. a stale Direct Share shortcut): choose again.
        if (forHere && gone) {
            Toast.makeText(ui, ui.getString(R.string.share_session_cannot_send), Toast.LENGTH_LONG).show()
            inbox.reopenPicker()
        }
    }
    LaunchedEffect(forHere, known, takes) {
        if (!forHere || !known) return@LaunchedEffect
        if (!takes) {
            Toast.makeText(ui, ui.getString(R.string.share_session_cannot_send), Toast.LENGTH_LONG).show()
            inbox.reopenPicker()
            return@LaunchedEffect
        }
        val content = inbox.takeFor(sessionId) ?: return@LaunchedEffect
        vm.setDraft(mergeDraft(vm.state.value.draft, content.text))
        val plan = planAttachments(content.items, vm.state.value.draftAttachments.size)
        if (plan.overflow.isNotEmpty()) Toast.makeText(ui, ui.getString(R.string.share_overflow_toast, plan.overflow.size, MAX_ATTACHMENTS), Toast.LENGTH_LONG).show()
        scope.launch(Dispatchers.IO) {
            plan.accepted.forEach { item ->
                val (name, size, mime) = ShareFiles.info(context, item)
                val local = item.cachedPath?.let { android.net.Uri.fromFile(java.io.File(it)).toString() } ?: item.uri
                vm.addAttachment(name, size, mime, localUri = local) { ShareFiles.open(context, item) }
            }
        }
    }
}
