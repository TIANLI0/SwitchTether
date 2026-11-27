package io.github.tianli0.switchtether.share

import io.github.tianli0.switchtether.remote.protocol.ControlLevel
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.sessions.MAX_ATTACHMENTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "Share to SwitchTether" (docs/ANDROID_UI.md §32). Everything here is plain Kotlin so it is unit
 * tested on the JVM; reading the intent, URI grants and the cache copy live in `ShareAndroid.kt`.
 */

/** One shared file: a content URI another app granted us read access to. */
data class SharedItem(
    val uri: String,
    /** The sender's type for it; null when only a wildcard (`image / *`) was given. */
    val mimeType: String?,
    /** The read grant was taken as persistable (it outlives the activity). */
    val persisted: Boolean = false,
    /** A copy in this app's cache, made when the grant would end before the upload. */
    val cachedPath: String? = null,
    /** The file's display name, kept with the cached copy. */
    val cachedName: String? = null,
)

/** What another app shared: text for the input box, files for the attachment pipeline. */
data class SharedContent(
    val text: String?,
    val items: List<SharedItem>,
    /** Entries that were not taken (not a `content:` URI, or one of this app's own providers). */
    val rejected: Int = 0,
    /** Direct Share: the session whose conversation shortcut was picked in the system share sheet. */
    val shortcutSessionId: String? = null,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && items.isEmpty()
}

/** The intent's fields, read on Android ([ShareIntents]) and parsed here. */
data class RawShare(
    val action: String?,
    val type: String?,
    /** `EXTRA_TEXT` (one for SEND, possibly several for SEND_MULTIPLE). */
    val texts: List<String> = emptyList(),
    val subject: String? = null,
    /** `EXTRA_STREAM` URIs. */
    val streams: List<String> = emptyList(),
    /** URIs and texts of the intent's ClipData (where the system puts the grants). */
    val clipUris: List<String> = emptyList(),
    val clipTexts: List<String> = emptyList(),
    /** `Intent.EXTRA_SHORTCUT_ID`: set when a Direct Share target (a session shortcut) was picked. */
    val shortcutId: String? = null,
)

object ShareIntake {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /** Shortcut category of session conversations that are Direct Share targets (res/xml/shortcuts.xml). */
    const val SHORTCUT_CATEGORY = "io.github.tianli0.switchtether.category.SESSION_SHARE"

    /** Same prefix as `ConversationPolicy.ID_PREFIX` (one long-lived shortcut per session). */
    private const val SHORTCUT_PREFIX = "session:"

    /** The session of a Direct Share shortcut id, null for anything else. */
    fun sessionOfShortcut(shortcutId: String?): String? =
        shortcutId?.takeIf { it.startsWith(SHORTCUT_PREFIX) && it.length > SHORTCUT_PREFIX.length }?.substring(SHORTCUT_PREFIX.length)

    fun isShare(action: String?): Boolean = action == ACTION_SEND || action == ACTION_SEND_MULTIPLE

    /**
     * The shared content, or null when [raw] is not a share or carries nothing usable.
     * [isOwnAuthority] guards against another app handing us a URI of our own provider (we could
     * read it, the sender could not): such entries are counted in [SharedContent.rejected].
     */
    fun parse(raw: RawShare, isOwnAuthority: (String) -> Boolean): SharedContent? {
        if (!isShare(raw.action)) return null
        val body = raw.texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { raw.clipTexts.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
            .joinToString("\n")
        val subject = raw.subject?.trim()?.takeIf { it.isNotEmpty() }
        // Browsers: subject = page title, text = URL. A subject already in the text is not repeated;
        // a subject alone (mail apps sharing a file) is not a message.
        val text = when {
            body.isEmpty() -> null
            subject != null && !body.contains(subject) -> "$subject\n$body"
            else -> body
        }
        val wildcard = raw.type?.contains('*') != false
        val uris = (raw.streams + raw.clipUris).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        var rejected = 0
        val items = uris.mapNotNull { u ->
            val scheme = u.substringBefore(':', "").lowercase()
            val authority = u.substringAfter("://", "").substringBefore('/').substringBefore('?')
            if (scheme != "content" || authority.isEmpty() || isOwnAuthority(authority)) {
                rejected++
                null
            } else {
                SharedItem(u, if (wildcard) null else raw.type)
            }
        }
        val content = SharedContent(text, items, rejected, sessionOfShortcut(raw.shortcutId))
        return if (content.isEmpty && rejected == 0) null else content
    }
}

/** Which shared files fit into a message that already has [alreadyAttached] attachments. */
data class AttachmentPlan(val accepted: List<SharedItem>, val overflow: List<SharedItem>)

fun planAttachments(items: List<SharedItem>, alreadyAttached: Int = 0, max: Int = MAX_ATTACHMENTS): AttachmentPlan {
    val room = (max - alreadyAttached).coerceAtLeast(0)
    return AttachmentPlan(items.take(room), items.drop(room))
}

/** Where a received share goes first. */
enum class ShareRoute {
    /** Not paired (or the 6-digit code is not confirmed yet): explain, then onboarding. */
    ONBOARDING,

    /** Paired but the computer is not reachable: the share waits (or the user cancels). */
    WAIT_FOR_COMPUTER,

    /** Pick an existing session or a new one. */
    PICK_TARGET,
}

fun shareRoute(paired: Boolean, verificationPending: Boolean, online: Boolean): ShareRoute = when {
    !paired || verificationPending -> ShareRoute.ONBOARDING
    !online -> ShareRoute.WAIT_FOR_COMPUTER
    else -> ShareRoute.PICK_TARGET
}

/**
 * A file whose read grant would end before it can be uploaded: the share waits (onboarding or an
 * offline computer) and the grant is neither persistable nor already copied. Only then is it
 * copied into the cache; a share that goes straight to a session streams from the sender's URI.
 */
fun needsStaging(item: SharedItem, route: ShareRoute): Boolean =
    route != ShareRoute.PICK_TARGET && !item.persisted && item.cachedPath == null

/**
 * Sessions that can take the shared message: ones the phone fully controls (SwitchTether-managed or
 * taken over with the opt-in hooks). A session attached through a terminal takes text only, so it
 * is left out when files were shared. Running ones first (working, then idle), newest activity next.
 */
fun shareTargets(sessions: List<SessionSummary>, hasFiles: Boolean): List<SessionSummary> =
    sessions.asSequence()
        .filter { it.controlLevel == ControlLevel.FULL }
        .filter { !hasFiles || it.terminalId == null }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<SessionSummary> { it.isLive }
                .thenByDescending { it.isLive && it.status == "busy" }
                .thenByDescending { it.lastActiveAtMs ?: 0L },
        )
        .toList()

/**
 * Direct Share: the shortcut's session when it can take this share (still listed and fully
 * controllable, and not text-only when files came); null = fall back to the picker.
 */
fun directShareTarget(sessionId: String?, sessions: List<SessionSummary>, hasFiles: Boolean): String? =
    sessionId?.takeIf { id -> shareTargets(sessions, hasFiles).any { it.id == id } }

/** The input box after a share: what was typed stays, the shared text follows on its own paragraph. */
fun mergeDraft(current: String, shared: String?): String {
    val add = shared?.trim().orEmpty()
    if (add.isEmpty()) return current
    if (current.isBlank()) return add
    if (current.contains(add)) return current
    return current.trimEnd() + "\n\n" + add
}

/**
 * The one pending share of the app (in memory; a newer share replaces it). The picker shows while
 * [Pending.stage] is [Stage.Picking]; the session page takes the content with [takeFor].
 */
class ShareInbox {
    sealed interface Stage {
        data object Picking : Stage
        /** Direct Share into [sessionId]: checked against the session list, then [Deliver] or [Picking]. */
        data class Direct(val sessionId: String) : Stage
        /** The new-session sheet is open with the shared text as its first message. */
        data object NewSession : Stage
        /** Opened in this session's composer (consumed by [takeFor]). */
        data class Deliver(val sessionId: String) : Stage
    }

    data class Pending(
        val content: SharedContent,
        val stage: Stage = Stage.Picking,
        /** The "pair first" explanation was confirmed (onboarding keeps the share). */
        val acknowledgedUnpaired: Boolean = false,
        /** "New session" was chosen: the session created next gets the files. */
        val toNewSession: Boolean = false,
    )

    private val _state = MutableStateFlow<Pending?>(null)
    val state: StateFlow<Pending?> = _state.asStateFlow()

    fun receive(content: SharedContent) {
        _state.value = Pending(content, stage = content.shortcutSessionId?.let { Stage.Direct(it) } ?: Stage.Picking)
    }

    /** The Direct Share session was checked: open it ([sessionId]) or, when null, show the picker. */
    fun resolveDirect(sessionId: String?) = _state.update { p ->
        if (p?.stage !is Stage.Direct) p
        else if (sessionId != null) p.copy(stage = Stage.Deliver(sessionId)) else p.copy(stage = Stage.Picking)
    }

    fun cancel() {
        _state.value = null
    }

    fun acknowledgeUnpaired() = _state.update { it?.copy(acknowledgedUnpaired = true) }

    fun updateContent(f: (SharedContent) -> SharedContent) = _state.update { it?.copy(content = f(it.content)) }

    fun pickSession(sessionId: String) = _state.update { it?.copy(stage = Stage.Deliver(sessionId), toNewSession = false) }

    fun pickNewSession() = _state.update { it?.copy(stage = Stage.NewSession, toNewSession = true) }

    /** The chosen session cannot take a message after all: choose again. */
    fun reopenPicker() = _state.update { if (it?.stage is Stage.Deliver || it?.stage is Stage.Direct) it.copy(stage = Stage.Picking) else it }

    /** The new-session sheet closed: back to the picker (a session created right after still gets the files). */
    fun newSessionClosed() = _state.update { if (it?.stage == Stage.NewSession) it.copy(stage = Stage.Picking) else it }

    /** A session was created from the sheet: when the share chose "new session", its files go there (the text already went into the first message). */
    fun sessionCreated(sessionId: String) = _state.update { p ->
        when {
            p == null || !p.toNewSession -> p
            p.content.items.isEmpty() -> null
            else -> p.copy(content = p.content.copy(text = null), stage = Stage.Deliver(sessionId), toNewSession = false)
        }
    }

    /** The content for [sessionId]'s composer, once; null when the share is not for it. */
    fun takeFor(sessionId: String): SharedContent? {
        while (true) {
            val p = _state.value ?: return null
            if (p.stage != Stage.Deliver(sessionId)) return null
            if (_state.compareAndSet(p, null)) return p.content
        }
    }
}
