package io.github.tianli0.switchtether.ui.sessions

import android.content.ClipData
import android.content.ClipDescription
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.share.ShareFiles
import io.github.tianli0.switchtether.share.ShareIntents
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A dropped or pasted clip as a [DropPayload]: item text (links included) and item URIs. */
fun ClipData.toDropPayload(description: ClipDescription? = this.description): DropPayload {
    val texts = ArrayList<String>()
    val uris = ArrayList<String>()
    for (i in 0 until itemCount) {
        val item = getItemAt(i)
        item.uri?.let { uris += it.toString() }
        item.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
    }
    val types = description?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }.orEmpty()
    return DropPayload(texts, uris, types)
}

/** Why nothing (or no file) could be added, for the toast and the drop highlight. */
@Composable
fun intakeBlockText(block: IntakeBlock): String? = when (block) {
    IntakeBlock.NONE -> null
    IntakeBlock.LOADING -> stringResource(R.string.drop_blocked_loading)
    IntakeBlock.OFFLINE_SNAPSHOT -> stringResource(R.string.drop_blocked_snapshot)
    IntakeBlock.OFFLINE -> stringResource(R.string.drop_blocked_offline)
    IntakeBlock.NO_CONTROL -> stringResource(R.string.drop_blocked_no_control)
}

private fun blockText(context: android.content.Context, block: IntakeBlock): String? = when (block) {
    IntakeBlock.NONE -> null
    IntakeBlock.LOADING -> context.getString(R.string.drop_blocked_loading)
    IntakeBlock.OFFLINE_SNAPSHOT -> context.getString(R.string.drop_blocked_snapshot)
    IntakeBlock.OFFLINE -> context.getString(R.string.drop_blocked_offline)
    IntakeBlock.NO_CONTROL -> context.getString(R.string.drop_blocked_no_control)
}

/** One line about everything that was not added (null = all of it was). */
fun intakeReport(context: android.content.Context, plan: IntakePlan): String? {
    blockText(context, plan.block)?.let { return it }
    val lines = buildList {
        if (plan.refusedFiles > 0) {
            add(
                when (plan.filesBlock) {
                    FilesBlock.TEXT_ONLY -> context.getString(R.string.drop_files_text_only, plan.refusedFiles)
                    else -> context.getString(R.string.drop_files_offline, plan.refusedFiles)
                },
            )
        }
        if (plan.overflow > 0) add(context.getString(R.string.drop_overflow, plan.overflow, MAX_ATTACHMENTS))
        if (plan.rejected > 0) add(context.getString(R.string.share_rejected, plan.rejected))
    }
    return lines.joinToString("\n").ifEmpty { null }
}

/**
 * The session composer's intake: dropped / committed / pasted content goes through [planIntake]
 * with the composer's gate; text joins the draft (unless the text field inserts it itself,
 * [textInField]), files go into the attachment pipeline exactly like picked ones (uploaded at
 * once, streamed, 1 GiB each), and whatever was left out is said in a toast.
 */
@Composable
fun rememberComposerIntake(vm: SessionDetailViewModel): (DropPayload, Boolean) -> Unit {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    return remember(vm) {
        { payload, textInField ->
            val st = vm.state.value
            val plan = planIntake(payload, composerGate(st), st.draftAttachments.size, ShareIntents.ownAuthority(app))
            if (!textInField && !plan.text.isNullOrBlank()) vm.setDraft(dropIntoDraft(vm.state.value.draft, plan.text))
            if (plan.accepted.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    plan.accepted.forEach { item ->
                        val (name, size, mime) = ShareFiles.info(app, item)
                        vm.addAttachment(name, size, mime, localUri = item.uri) { ShareFiles.open(app, item) }
                    }
                }
            }
            intakeReport(context, plan)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
}

/**
 * A drop target for content from other apps. Any drag with a MIME type is offered (the highlight
 * then says whether it would be taken); on drop the read grant for the clip's URIs is requested
 * from the activity (`requestDragAndDropPermissions`, held until the activity ends so the upload
 * can stream the file) and the clip is handed to [onDrop].
 */
@Composable
fun Modifier.contentDropTarget(onHover: (Boolean) -> Unit, onDrop: (DropPayload) -> Unit): Modifier {
    val activity = LocalContext.current as? android.app.Activity
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val dragEvent = event.toAndroidDragEvent()
                val clip = dragEvent.clipData ?: return false
                val hasUris = (0 until clip.itemCount).any { clip.getItemAt(it).uri != null }
                if (hasUris) runCatching { activity?.requestDragAndDropPermissions(dragEvent) }
                drop(clip.toDropPayload(dragEvent.clipDescription ?: clip.description))
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().isNotEmpty() }, target = target)
}

/**
 * For the composer's text field (`Modifier.contentReceiver`): images, GIFs and stickers the
 * keyboard commits, pictures pasted from the clipboard and drops onto the field. Items with a
 * file URI go to [onFiles]; text (and links) is left to the field, which inserts it at the caret.
 */
@OptIn(ExperimentalFoundationApi::class)
fun composerContentListener(onHover: (Boolean) -> Unit, onFiles: (DropPayload) -> Unit): ReceiveContentListener =
    object : ReceiveContentListener {
        override fun onDragEnter() = onHover(true)
        override fun onDragExit() = onHover(false)
        override fun onDragEnd() = onHover(false)
        override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
            val uris = ArrayList<String>()
            val rest = transferableContent.consume { item ->
                val u = item.uri ?: return@consume false
                if (u.scheme.equals("http", true) || u.scheme.equals("https", true)) return@consume false
                uris += u.toString()
                true
            }
            if (uris.isNotEmpty()) {
                val d = transferableContent.clipMetadata.clipDescription
                onFiles(DropPayload(uris = uris, mimeTypes = (0 until d.mimeTypeCount).map { d.getMimeType(it) }))
            }
            return rest
        }
    }

/** The highlight over a drop target while something is dragged over it: what a drop would do. */
@Composable
fun BoxScope.DropHighlight(visible: Boolean, label: String, blocked: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val color = if (blocked) LocalDevColors.current.warn else COUITheme.colorScheme.primary
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .matchParentSize()
            .padding(8.dp)
            .border(2.dp, color, shape)
            .background(color.copy(alpha = 0.08f), shape)
            .testTag("drop-highlight"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = COUITheme.textStyles.body1,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(COUITheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
