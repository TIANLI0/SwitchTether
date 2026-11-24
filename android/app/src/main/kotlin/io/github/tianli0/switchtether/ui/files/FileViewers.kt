package io.github.tianli0.switchtether.ui.files

import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.SmallButton
import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import io.github.suqi8.coui.kmp.basic.Icon
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.subsamplingimage.util.canBeSubSampled
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.platform.testTag
import io.github.suqi8.coui.kmp.basic.IntentSlider
import io.github.suqi8.coui.kmp.basic.IntentSliderPlaybackState
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.files.DownloadRecord
import io.github.tianli0.switchtether.files.FileKind
import io.github.tianli0.switchtether.files.FileTooLargeException
import io.github.tianli0.switchtether.files.LocalFile
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to show: the remote file's identity. The viewer resolves a local copy itself (download → cache → fetch). */
data class RemoteFileRef(val path: String, val name: String, val kind: FileKind, val size: Long? = null, val modifiedMs: Long? = null)

private sealed interface Phase {
    data class Loading(val done: Long = 0, val total: Long? = null) : Phase
    data class Ready(val file: LocalFile) : Phase
    data class Failed(val message: String, val tooLarge: Boolean = false) : Phase
}

private data class SaveState(val done: Long = 0, val total: Long? = null, val error: String? = null, val record: DownloadRecord? = null, val running: Boolean = false)

/**
 * "Download to phone" for one remote file, shared by every viewer state (a preview, a file the app
 * cannot show, a text file): progress, the saved record, or the error.
 */
@Stable
private class SaveController(private val ref: RemoteFileRef, private val store: io.github.tianli0.switchtether.files.FileStore, private val scope: kotlinx.coroutines.CoroutineScope) {
    var state by mutableStateOf(SaveState())
        private set

    fun start() {
        if (state.running) return
        state = SaveState(running = true)
        scope.launch {
            state = try {
                val r = store.download(ref.path, ref.name, ref.size, ref.modifiedMs) { n, total -> state = SaveState(n, total, running = true) }
                SaveState(record = r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SaveState(error = (e as? ApiException)?.body?.message ?: ConnectionManager.describe(e))
            }
        }
    }
}

/**
 * Full-pane viewer for a remote pdf / image / video / audio / text file, or any other file, which is
 * offered to keep or to hand to another app. Opening makes a TEMPORARY cache copy (or reuses a
 * download the user already saved) and never creates a download; "Download" is the explicit way to
 * keep the file. Self-contained so the Files page and chat share it.
 */
@Composable
fun RemoteFileViewer(
    ref: RemoteFileRef,
    onBack: () -> Unit,
    onManageDownloads: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** A file link inside a rendered Markdown file; null leaves such links inert. */
    onOpenPath: ((String) -> Unit)? = null,
) {
    val store = appContainer().fileStore
    val scope = rememberCoroutineScope()
    val save = remember(ref) { SaveController(ref, store, scope) }
    // A text file that turned out to be binary continues as a file to keep or hand off.
    var asBinary by remember(ref.path) { mutableStateOf(false) }
    val textLike = ref.kind == FileKind.TEXT || (ref.kind == FileKind.OTHER && io.github.tianli0.switchtether.files.FileTypes.extension(ref.name).isEmpty())
    when {
        textLike && !asBinary -> RemoteTextFileViewer(ref, onBack, modifier, onOpenPath, save, onManageDownloads, onBinary = { asBinary = true })
        ref.kind == FileKind.OTHER || asBinary -> HandOffViewer(ref, onBack, onManageDownloads, modifier, save)
        else -> MediaFileViewer(ref, onBack, onManageDownloads, modifier, save)
    }
}

@Composable
private fun MediaFileViewer(ref: RemoteFileRef, onBack: () -> Unit, onManageDownloads: (() -> Unit)?, modifier: Modifier, save: SaveController) {
    val store = appContainer().fileStore
    val context = LocalContext.current
    var reload by remember(ref.path) { mutableIntStateOf(0) }
    val phase by produceState<Phase>(Phase.Loading(), ref.path, reload) {
        value = Phase.Loading()
        value = try {
            Phase.Ready(store.resolve(ref.path, ref.name, ref.size, ref.modifiedMs, forceRemote = reload > 0) { n, total -> value = Phase.Loading(n, total) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileTooLargeException) {
            Phase.Failed(Formatters.bytes(e.limitBytes), tooLarge = true)
        } catch (e: Exception) {
            Phase.Failed((e as? ApiException)?.body?.message ?: ConnectionManager.describe(e))
        }
    }
    val ready = (phase as? Phase.Ready)?.file
    val noApp = stringResource(R.string.viewer_no_app)
    // Pictures, PDF pages and video sit on black: the page around them goes dark too.
    val dark = ref.kind == FileKind.IMAGE || ref.kind == FileKind.VIDEO

    Column(modifier.fillMaxSize()) {
        val where = ready?.let { stringResource(if (it.fromDownload) R.string.viewer_saved_copy else R.string.viewer_temp_copy) }
        FilePreviewHeader(
            title = ref.name,
            subtitle = listOfNotNull(previewFolder(ref.path), (ready?.size ?: ref.size)?.let(Formatters::bytes), where).joinToString(" · "),
            onBack = onBack,
        ) {
            if (ready != null) {
                ToolbarIcon(AppIcons.Share, stringResource(R.string.viewer_share), { startSafely(context, store.shareIntent(ready), noApp) })
                ToolbarIcon(AppIcons.OpenWith, stringResource(R.string.viewer_open_with), { startSafely(context, store.viewIntent(ready), noApp) })
                if (!ready.fromDownload) ToolbarIcon(AppIcons.Refresh, stringResource(R.string.viewer_refresh), { reload++ })
            }
            ToolbarIcon(AppIcons.Download, stringResource(R.string.files_download), save::start, enabled = !save.state.running && ready?.fromDownload != true)
        }
        // The save line grows in under the header instead of pushing the preview down at once.
        io.github.tianli0.switchtether.ui.common.NoticeVisibility(save.state.running || save.state.error != null || save.state.record != null) {
            SaveRow(save.state, onManageDownloads) { r -> startSafely(context, store.viewIntent(store.asLocal(r)), noApp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(if (dark && phase is Phase.Ready) Modifier.background(Color.Black) else Modifier)) {
            when (val p = phase) {
                is Phase.Loading -> LoadingBody(p.done, p.total)
                is Phase.Failed -> if (p.tooLarge) {
                    // Too big for a temporary copy, but nothing stops keeping it.
                    FileHandOffCard(
                        name = ref.name,
                        detail = listOfNotNull(ref.size?.let(Formatters::bytes), previewFolder(ref.path)).joinToString(" · "),
                        message = stringResource(R.string.viewer_too_large, p.message),
                        progress = save.state.takeIf { it.running }?.let { it.done to it.total },
                        progressLabel = save.state.takeIf { it.running }?.let { stringResource(R.string.viewer_saving, progressText(it.done, it.total)) },
                        primary = if (save.state.record == null) stringResource(R.string.files_download) to save::start else null,
                        secondary = save.state.record?.let { r -> stringResource(R.string.viewer_open_with) to { startSafely(context, store.viewIntent(store.asLocal(r)), noApp) } },
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.viewer_failed, p.message),
                            style = COUITheme.textStyles.body2, color = LocalDevColors.current.danger, textAlign = TextAlign.Center,
                        )
                        SmallButton(stringResource(R.string.action_retry), { reload++ }, modifier = Modifier.padding(top = 16.dp))
                    }
                }
                is Phase.Ready -> LocalFileBody(p.file, ref.kind, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * A file the app has no viewer for (an archive, an installer, an office document, a binary). Nothing
 * is fetched until asked: "Download" keeps it in Downloads, "Open with…" fetches a temporary copy and
 * hands it to another app. It used to fetch the whole file first and then only say there was no viewer.
 */
@Composable
private fun HandOffViewer(ref: RemoteFileRef, onBack: () -> Unit, onManageDownloads: (() -> Unit)?, modifier: Modifier, save: SaveController) {
    val store = appContainer().fileStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.viewer_no_app)
    var opening by remember(ref.path) { mutableStateOf<Phase?>(null) }
    val openWith: () -> Unit = {
        if (opening !is Phase.Loading) {
            opening = Phase.Loading()
            scope.launch {
                opening = try {
                    val f = store.resolve(ref.path, ref.name, ref.size, ref.modifiedMs) { n, total -> opening = Phase.Loading(n, total) }
                    startSafely(context, store.viewIntent(f), noApp)
                    Phase.Ready(f)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileTooLargeException) {
                    Phase.Failed(Formatters.bytes(e.limitBytes), tooLarge = true)
                } catch (e: Exception) {
                    Phase.Failed((e as? ApiException)?.body?.message ?: ConnectionManager.describe(e))
                }
            }
        }
    }
    val local = (opening as? Phase.Ready)?.file
    Column(modifier.fillMaxSize()) {
        FilePreviewHeader(
            title = ref.name,
            subtitle = listOfNotNull(previewFolder(ref.path), ref.size?.let(Formatters::bytes)).joinToString(" · "),
            onBack = onBack,
        ) {
            if (local != null) ToolbarIcon(AppIcons.Share, stringResource(R.string.viewer_share), { startSafely(context, store.shareIntent(local), noApp) })
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val saving = save.state.running
            val fetching = opening as? Phase.Loading
            val saved = save.state.record
            FileHandOffCard(
                name = ref.name,
                detail = listOfNotNull(ref.size?.let(Formatters::bytes), kindLabel(ref.name)).joinToString(" · "),
                message = when {
                    save.state.error != null -> save.state.error
                    (opening as? Phase.Failed)?.tooLarge == true -> stringResource(R.string.viewer_too_large, (opening as Phase.Failed).message)
                    opening is Phase.Failed -> stringResource(R.string.viewer_failed, (opening as Phase.Failed).message)
                    saved != null -> stringResource(R.string.viewer_saved_to, io.github.tianli0.switchtether.files.FileStore.DOWNLOAD_FOLDER)
                    else -> stringResource(R.string.viewer_no_viewer)
                },
                progress = when {
                    saving -> save.state.done to save.state.total
                    fetching != null -> fetching.done to fetching.total
                    else -> null
                },
                progressLabel = when {
                    saving -> stringResource(R.string.viewer_saving, progressText(save.state.done, save.state.total))
                    fetching != null -> stringResource(R.string.viewer_loading, progressText(fetching.done, fetching.total))
                    else -> null
                },
                primary = if (saved != null) {
                    stringResource(R.string.downloads_open) to { startSafely(context, store.viewIntent(store.asLocal(saved)), noApp) }
                } else {
                    stringResource(R.string.files_download) to save::start
                },
                secondary = if (saved == null) stringResource(R.string.viewer_open_with) to openWith else null,
                tertiary = if (saved != null && onManageDownloads != null) stringResource(R.string.viewer_manage) to onManageDownloads else null,
            )
        }
    }
}

@Composable
private fun LoadingBody(done: Long, total: Long?) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.viewer_loading, progressText(done, total)),
            style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
        )
        LinearProgressIndicator(progress = total?.let { if (it > 0) done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

private fun progressText(done: Long, total: Long?): String = Formatters.bytes(done) + (total?.let { " / " + Formatters.bytes(it) } ?: "")

/** "ZIP archive"-style second line: the extension in capitals is enough and needs no translation table. */
private fun kindLabel(name: String): String? = io.github.tianli0.switchtether.files.FileTypes.extension(name).takeIf { it.isNotEmpty() }?.uppercase()

/** Source and text links use fs/read and remain in memory, just like the workspace editor. */
@Composable
private fun RemoteTextFileViewer(
    ref: RemoteFileRef,
    onBack: () -> Unit,
    modifier: Modifier,
    onOpenPath: ((String) -> Unit)?,
    save: SaveController,
    onManageDownloads: (() -> Unit)?,
    onBinary: () -> Unit,
) {
    val connection = appContainer().connection
    val store = appContainer().fileStore
    val context = LocalContext.current
    val noApp = stringResource(R.string.viewer_no_app)
    var reload by remember(ref.path) { mutableIntStateOf(0) }
    val content by produceState<io.github.tianli0.switchtether.ui.common.Loadable<io.github.tianli0.switchtether.remote.protocol.FileContent>>(
        io.github.tianli0.switchtether.ui.common.Loadable.Loading, ref.path, reload,
    ) {
        value = io.github.tianli0.switchtether.ui.common.Loadable.Loading
        value = io.github.tianli0.switchtether.ui.common.loadable {
            val api = connection.apiOrNull() ?: throw io.github.tianli0.switchtether.connection.NotConnectedException()
            api.fsRead(ref.path)
        }
    }
    val loaded = content.valueOrNull
    Column(modifier.fillMaxSize()) {
        FilePreviewHeader(
            title = ref.name,
            subtitle = listOfNotNull(
                previewFolder(ref.path),
                (loaded?.size ?: ref.size)?.let(Formatters::bytes),
                loaded?.text?.let { t -> stringResource(R.string.files_line_count, t.count { it == '\n' } + 1) },
            ).joinToString(" · "),
            onBack = onBack,
        ) {
            ToolbarIcon(AppIcons.Refresh, stringResource(R.string.viewer_refresh), { reload++ })
            ToolbarIcon(AppIcons.Download, stringResource(R.string.files_download), save::start, enabled = !save.state.running)
        }
        io.github.tianli0.switchtether.ui.common.NoticeVisibility(save.state.running || save.state.error != null || save.state.record != null) {
            SaveRow(save.state, onManageDownloads) { r -> startSafely(context, store.viewIntent(store.asLocal(r)), noApp) }
        }
        io.github.tianli0.switchtether.ui.common.LoadableContent(content, onRetry = { reload++ }) { file ->
            // A large file's notice and a binary file both offer Download; a binary also "Open with…".
            TextFileBody(file, Modifier.weight(1f), onOpenPath = onOpenPath, onDownload = save::start, onOpenBinary = onBinary)
        }
    }
}

@Composable
private fun SaveRow(save: SaveState, onManage: (() -> Unit)?, onOpen: (DownloadRecord) -> Unit) {
    if (!save.running && save.error == null && save.record == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        when {
            save.running -> {
                Text(stringResource(R.string.viewer_saving, progressText(save.done, save.total)), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary)
                LinearProgressIndicator(progress = save.total?.let { if (it > 0) save.done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            save.error != null -> Text(save.error, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger)
            save.record != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.viewer_saved_to, io.github.tianli0.switchtether.files.FileStore.DOWNLOAD_FOLDER), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.ok, modifier = Modifier.weight(1f))
                LinkButton(stringResource(R.string.downloads_open), { onOpen(save.record) })
                if (onManage != null) LinkButton(stringResource(R.string.viewer_manage), onManage)
            }
        }
    }
}

/** Starts [intent]; a missing handler is reported instead of crashing. */
internal fun startSafely(context: Context, intent: Intent, noApp: String) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
    }
}

/** The in-app renderer for a local copy: image / pdf / video / audio; anything else only gets the hand-off buttons above. */
@Composable
fun LocalFileBody(file: LocalFile, kind: FileKind, modifier: Modifier = Modifier) {
    when (kind) {
        FileKind.IMAGE -> ZoomableImage(file, modifier)
        FileKind.PDF -> PdfPages(file, modifier)
        FileKind.VIDEO -> MediaPlayerBody(file, video = true, modifier)
        FileKind.AUDIO -> MediaPlayerBody(file, video = false, modifier)
        else -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.viewer_no_viewer), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
}

// ---- image ------------------------------------------------------------------------------------------------

private const val MAX_IMAGE_DIM = 3072

/**
 * Pinch / double-tap zoom, pan and fling via Telephoto (Saket Narayan, Apache-2.0). JPEG / PNG / WebP / HEIF
 * are shown with `SubSamplingImage`: tiles are decoded at the zoom level on screen, so a 50-megapixel photo stays
 * sharp at full zoom without running out of memory. GIF / AVIF / BMP / ICO (not tileable by Android's region
 * decoder) are decoded once, down-sampled, and zoomed with `Modifier.zoomable`.
 */
@Composable
private fun ZoomableImage(file: LocalFile, modifier: Modifier) {
    val context = LocalContext.current
    val source = remember(file.uri) { SubSamplingImageSource.contentUriOrNull(file.uri) }
    val tileable by produceState<Boolean?>(null, source) {
        value = source != null && runCatching { source.canBeSubSampled(context) }.getOrDefault(false)
    }
    var tilingFailed by remember(file.uri) { mutableStateOf(false) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            tileable == null -> ImageMessage(stringResource(R.string.state_loading))
            tileable == true && source != null && !tilingFailed -> TiledImage(file, source) { tilingFailed = true }
            else -> DecodedZoomableImage(file)
        }
    }
}

@Composable
private fun TiledImage(file: LocalFile, source: SubSamplingImageSource, onFailed: () -> Unit) {
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f))
    val reporter = remember(onFailed) {
        object : SubSamplingImageErrorReporter {
            override fun onImageLoadingFailed(e: java.io.IOException, imageSource: SubSamplingImageSource) = onFailed()
        }
    }
    val state = rememberSubSamplingImageState(imageSource = source, zoomableState = zoom, errorReporter = reporter)
    SubSamplingImage(state = state, contentDescription = file.name, modifier = Modifier.fillMaxSize().zoomable(zoom))
    if (!state.isImageDisplayed) ImageMessage(stringResource(R.string.state_loading))
}

@Composable
private fun DecodedZoomableImage(file: LocalFile) {
    val context = LocalContext.current
    val bitmap by produceState<Result<ImageBitmap>?>(null, file.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, file.uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    // Large images are decoded at reduced size: bounded memory, still sharp when zoomed on a phone.
                    val longSide = maxOf(info.size.width, info.size.height)
                    if (longSide > MAX_IMAGE_DIM) decoder.setTargetSampleSize((longSide + MAX_IMAGE_DIM - 1) / MAX_IMAGE_DIM)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }
        }
    }
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 6f))
    val bmp = bitmap?.getOrNull()
    LaunchedEffect(bmp) {
        if (bmp != null) zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(Size(bmp.width.toFloat(), bmp.height.toFloat())))
    }
    when {
        bitmap == null -> ImageMessage(stringResource(R.string.state_loading))
        bmp == null -> ImageMessage(stringResource(R.string.viewer_image_failed))
        else -> Image(bitmap = bmp, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().zoomable(zoom))
    }
}

@Composable
private fun ImageMessage(text: String) {
    Text(text, style = COUITheme.textStyles.body2, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
}

// ---- pdf --------------------------------------------------------------------------------------------------

/** A PdfRenderer is single-page-at-a-time and not thread-safe: every access, including close, takes [lock]. */
private class PdfDoc(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val lock = Any()
    private var closed = false
    val pageCount: Int get() = renderer.pageCount

    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceAtLeast(64)
            val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(64, 8192)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }
}

/**
 * Android's built-in `PdfRenderer` (no extra library): a lazily rendered page list; tapping a page opens it
 * full screen at higher resolution with Telephoto zoom, swiping sideways between pages.
 */
@Composable
private fun PdfPages(file: LocalFile, modifier: Modifier) {
    val context = LocalContext.current
    val doc by produceState<Result<PdfDoc>?>(null, file.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val fd = context.contentResolver.openFileDescriptor(file.uri, "r") ?: error("cannot open")
                try {
                    PdfDoc(fd, PdfRenderer(fd)) // SecurityException for a password-protected pdf, IOException for a damaged one
                } catch (t: Throwable) {
                    runCatching { fd.close() }
                    throw t
                }
            }
        }
    }
    DisposableEffect(doc) { onDispose { doc?.getOrNull()?.close() } }
    val d = doc
    when {
        d == null -> Box(modifier, contentAlignment = Alignment.Center) { Text(stringResource(R.string.state_loading), color = COUITheme.colorScheme.onSurfaceVariantSummary) }
        d.isFailure -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.viewer_pdf_failed), style = COUITheme.textStyles.body2, color = LocalDevColors.current.danger, textAlign = TextAlign.Center)
        }
        else -> {
            val pdf = d.getOrThrow()
            val listState = rememberLazyListState()
            io.github.suqi8.coui.kmp.basic.BindScrollToTop(listState)
            var zoomedPage by remember(file.uri) { mutableStateOf<Int?>(null) }
            BoxWithConstraints(modifier.background(COUITheme.colorScheme.surfaceContainer)) {
                val widthPx = constraints.maxWidth.coerceAtMost(2000)
                LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until pdf.pageCount).toList(), key = { it }) { index ->
                        val page by produceState<ImageBitmap?>(null, index, widthPx) {
                            value = withContext(Dispatchers.IO) { pdf.render(index, widthPx)?.asImageBitmap() }
                        }
                        val bmp = page
                        val label = stringResource(R.string.viewer_pdf_page, index + 1, pdf.pageCount)
                        val pageModifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.files_pdf_zoom)) { zoomedPage = index }
                        if (bmp != null) {
                            Image(bmp, contentDescription = label, contentScale = ContentScale.FillWidth, modifier = pageModifier)
                        } else {
                            Box(pageModifier.aspectRatio(0.72f).background(Color.White))
                        }
                    }
                }
                PageBadge(stringResource(R.string.viewer_pdf_page, listState.firstVisibleItemIndex + 1, pdf.pageCount), Modifier.align(Alignment.BottomCenter))
                zoomedPage?.let { start ->
                    BackHandler { zoomedPage = null }
                    PdfZoomPager(pdf, start, (constraints.maxWidth * 2).coerceIn(800, 2400), onClose = { page ->
                        zoomedPage = null
                        listState.requestScrollToItem(page)
                    })
                }
            }
        }
    }
}

@Composable
private fun PdfZoomPager(pdf: PdfDoc, start: Int, renderWidthPx: Int, onClose: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { pdf.pageCount }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 0, key = { it }, modifier = Modifier.fillMaxSize()) { index ->
            val page by produceState<ImageBitmap?>(null, index, renderWidthPx) {
                value = withContext(Dispatchers.IO) { pdf.render(index, renderWidthPx)?.asImageBitmap() }
            }
            val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 5f))
            val bmp = page
            LaunchedEffect(bmp) {
                if (bmp != null) zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(Size(bmp.width.toFloat(), bmp.height.toFloat())))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    Image(bmp, contentDescription = stringResource(R.string.viewer_pdf_page, index + 1, pdf.pageCount), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().zoomable(zoom))
                } else {
                    ImageMessage(stringResource(R.string.state_loading))
                }
            }
        }
        val closeLabel = stringResource(R.string.action_close)
        Box(
            Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClickLabel = closeLabel) { onClose(pager.currentPage) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Close, contentDescription = closeLabel, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        PageBadge(stringResource(R.string.viewer_pdf_page, pager.currentPage + 1, pdf.pageCount), Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PageBadge(text: String, modifier: Modifier) {
    Text(
        text,
        style = COUITheme.textStyles.footnote1, color = Color.White,
        modifier = modifier.padding(bottom = 12.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ---- video / audio ----------------------------------------------------------------------------------------

@Composable
private fun MediaPlayerBody(file: LocalFile, video: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(file.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.uri))
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember(player) { mutableStateOf(true) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var buffered by remember(player) { mutableLongStateOf(0L) }
    var buffering by remember(player) { mutableStateOf(true) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    var aspect by remember(player) { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            buffered = player.bufferedPosition
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            delay(250)
        }
    }
    Column(modifier.background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (video) {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                    onRelease = { player.clearVideoSurfaceView(it) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.2f, 5f)),
                )
            } else {
                Text(file.name, style = COUITheme.textStyles.body1, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
            error?.let { Text(stringResource(R.string.viewer_media_error, it), style = COUITheme.textStyles.body2, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
        }
        val playback = when {
            error != null -> IntentSliderPlaybackState.Paused
            buffering && duration <= 0 -> IntentSliderPlaybackState.LoadingInitial
            buffering -> IntentSliderPlaybackState.LoadingBuffering
            playing -> IntentSliderPlaybackState.Playing
            else -> IntentSliderPlaybackState.Paused
        }
        MediaProgress(position, duration, buffered, playback) { player.seekTo(it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton(
                text = stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play),
                onClick = {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (player.isPlaying) player.pause() else player.play()
                },
            )
            Text("${clock(position)} / ${clock(duration)}", style = COUITheme.textStyles.footnote1, color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

/**
 * COUI 17 media progress bar (`COUIIntentSeekBar` via coui17 `IntentSlider`) in its over-media style:
 * fixed white palette, conic light trailing the progress while playing or dragging, thumb while
 * touched or paused, the buffered position underneath and a loading sweep while buffering. A drag
 * moves by the finger's travel and seeks once on release, so the 250 ms position poll never fights it.
 */
@Composable
private fun MediaProgress(position: Long, duration: Long, buffered: Long, playback: IntentSliderPlaybackState, onSeek: (Long) -> Unit) {
    val total = duration.coerceAtLeast(0L)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val max = if (total > 0) total.toFloat() else 1f
    IntentSlider(
        value = dragValue ?: position.coerceIn(0L, total).toFloat().coerceAtMost(max),
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            dragValue?.let { if (total > 0) onSeek(it.toLong().coerceIn(0L, total)) }
            dragValue = null
        },
        enabled = total > 0,
        showThumb = true,
        followSystemTheme = false,
        conicLight = true,
        playbackState = playback,
        valueRange = 0f..max,
        secondaryValue = if (total > 0) buffered.coerceIn(0L, total).toFloat() else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp).testTag("media-progress"),
    )
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
