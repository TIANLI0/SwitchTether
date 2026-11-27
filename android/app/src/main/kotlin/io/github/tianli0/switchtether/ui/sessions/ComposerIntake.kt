package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.switchtether.share.RawShare
import io.github.tianli0.switchtether.share.ShareIntake
import io.github.tianli0.switchtether.share.SharedItem
import io.github.tianli0.switchtether.share.planAttachments
import io.github.tianli0.switchtether.ui.common.Loadable

/**
 * Content that reaches a composer without the attach sheet (docs/ANDROID_UI.md §35): a drag and drop
 * from another app (split screen, tablets, DeX, desktop windows), an image or GIF the keyboard
 * commits, a picture pasted from the clipboard. Plain Kotlin; the same rules as a share (§32):
 * only `content:` URIs of other apps, at most [MAX_ATTACHMENTS] files per message, 1 GiB each
 * (enforced by the attachment pipeline), nothing is ever sent by itself.
 */
data class DropPayload(
    val texts: List<String> = emptyList(),
    /** URIs of the clip items, as given (links are moved to the text by [planIntake]). */
    val uris: List<String> = emptyList(),
    /** The clip description's MIME types. */
    val mimeTypes: List<String> = emptyList(),
)

/** Why a composer takes nothing at all. */
enum class IntakeBlock { NONE, LOADING, OFFLINE_SNAPSHOT, OFFLINE, NO_CONTROL }

/** Why files are left out while text is still taken. */
enum class FilesBlock { NONE, OFFLINE, TEXT_ONLY }

data class ComposerGate(val block: IntakeBlock, val files: FilesBlock = FilesBlock.NONE) {
    val acceptsText: Boolean get() = block == IntakeBlock.NONE
    val acceptsFiles: Boolean get() = block == IntakeBlock.NONE && files == FilesBlock.NONE
}

/**
 * The session composer's gate: the same rules as its send key and attach key. Not taking input
 * at all (offline snapshot, read-only session, not loaded) refuses everything with that reason.
 * Files are uploaded at once, so they need the connection (text can wait in the queue), and a
 * session attached through a terminal takes text only.
 */
fun composerGate(state: SessionDetailState): ComposerGate {
    val typing = state.canSend || state.canQueue
    val block = when {
        typing -> IntakeBlock.NONE
        !state.online && state.snapshotAtMs != null -> IntakeBlock.OFFLINE_SNAPSHOT
        !state.online -> IntakeBlock.OFFLINE
        state.detail !is Loadable.Ready -> IntakeBlock.LOADING
        else -> IntakeBlock.NO_CONTROL
    }
    val files = when {
        block != IntakeBlock.NONE -> FilesBlock.NONE
        !state.online -> FilesBlock.OFFLINE
        state.detail.valueOrNull?.summary?.terminalId != null -> FilesBlock.TEXT_ONLY
        else -> FilesBlock.NONE
    }
    return ComposerGate(block, files)
}

/** The new-session launcher: its first message and files need the connection like starting does. */
fun newSessionGate(online: Boolean): ComposerGate = ComposerGate(if (online) IntakeBlock.NONE else IntakeBlock.OFFLINE)

/** What to do with a payload. */
data class IntakePlan(
    val block: IntakeBlock = IntakeBlock.NONE,
    val text: String? = null,
    val accepted: List<SharedItem> = emptyList(),
    /** Files over the per-message limit. */
    val overflow: Int = 0,
    /** Entries that are not readable `content:` URIs of another app (e.g. `file:`, our own provider). */
    val rejected: Int = 0,
    /** Readable files left out because of [filesBlock]. */
    val refusedFiles: Int = 0,
    val filesBlock: FilesBlock = FilesBlock.NONE,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && accepted.isEmpty()
}

private fun isLink(u: String): Boolean = u.substringBefore(':', "").lowercase() in setOf("http", "https")

/**
 * Classifies [payload] for a composer with [gate] that already holds [alreadyAttached] files.
 * Parsing reuses the share intake ([ShareIntake.parse]): de-duplicated text, `content:` URIs of
 * other apps only. A dragged web link is text, not a file.
 */
fun planIntake(payload: DropPayload, gate: ComposerGate, alreadyAttached: Int, isOwnAuthority: (String) -> Boolean): IntakePlan {
    if (gate.block != IntakeBlock.NONE) return IntakePlan(block = gate.block)
    val (links, uris) = payload.uris.partition(::isLink)
    val raw = RawShare(
        action = ShareIntake.ACTION_SEND_MULTIPLE,
        type = payload.mimeTypes.distinct().singleOrNull() ?: "*/*",
        texts = payload.texts + links,
        clipUris = uris,
    )
    val content = ShareIntake.parse(raw, isOwnAuthority) ?: return IntakePlan()
    if (!gate.acceptsFiles) {
        return IntakePlan(text = content.text, rejected = content.rejected, refusedFiles = content.items.size, filesBlock = gate.files)
    }
    val plan = planAttachments(content.items, alreadyAttached)
    return IntakePlan(text = content.text, accepted = plan.accepted, overflow = plan.overflow.size, rejected = content.rejected)
}

/**
 * Where dropped text goes: at the caret when the drop hit the field (the field inserts it itself),
 * otherwise after what was typed, on its own paragraph (the share rule, [io.github.tianli0.switchtether.share.mergeDraft]).
 */
fun dropIntoDraft(current: String, text: String?): String = io.github.tianli0.switchtether.share.mergeDraft(current, text)
