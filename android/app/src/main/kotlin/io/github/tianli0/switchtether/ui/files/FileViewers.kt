package io.github.tianli0.switchtether.ui.files

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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
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
import io.github.tianli0.switchtether.ui.common.ToolbarIcon
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
 * Full-pane viewer for a remote pdf / image / video / audio (or any other file, offered to other apps).
 * Opening makes a TEMPORARY cache copy (or reuses a download the user already saved) and never creates a
 * download; "Download" is the explicit way to keep the file. Self-contained so the Files page and chat share it.
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
    if (ref.kind == FileKind.TEXT || (ref.kind == FileKind.OTHER && io.github.tianli0.switchtether.files.FileTypes.extension(ref.name).isEmpty())) {
        RemoteTextFileViewer(ref, onBack, modifier, onOpenPath)
        return
    }
    val store = appContainer().fileStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember(ref.path) { mutableIntStateOf(0) }
    var save by remember(ref.path) { mutableStateOf(SaveState()) }
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
            ToolbarIcon(
                AppIcons.Download, stringResource(R.string.files_download),
                onClick = {
                    if (save.running) return@ToolbarIcon
                    save = SaveState(running = true)
                    scope.launch {
                        save = try {
                            val r = store.download(ref.path, ref.name) { n, total -> save = SaveState(n, total, running = true) }
                            SaveState(record = r)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            SaveState(error = (e as? ApiException)?.body?.message ?: ConnectionManager.describe(e))
                        }
                    }
                },
                enabled = !save.running && ready?.fromDownload != true,
            )
        }
        SaveRow(save, onManageDownloads) { r -> startSafely(context, store.viewIntent(store.asLocal(r)), noApp) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val p = phase) {
                is Phase.Loading -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.viewer_loading, Formatters.bytes(p.done) + (p.total?.let { " / " + Formatters.bytes(it) } ?: "")),
                        style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LinearProgressIndicator(progress = p.total?.let { if (it > 0) p.done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                is Phase.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (p.tooLarge) stringResource(R.string.viewer_too_large, p.message) else stringResource(R.string.viewer_failed, p.message),
                        style = COUITheme.textStyles.body2, color = LocalDevColors.current.danger, textAlign = TextAlign.Center,
                    )
                    if (!p.tooLarge) TextButton(text = stringResource(R.string.action_retry), onClick = { reload++ })
                }
                is Phase.Ready -> LocalFileBody(p.file, ref.kind, Modifier.fillMaxSize())
            }
        }
    }
}

/** Source and text links use fs/read and remain in memory, just like the workspace editor. */
@Composable
private fun RemoteTextFileViewer(ref: RemoteFileRef, onBack: () -> Unit, modifier: Modifier, onOpenPath: ((String) -> Unit)?) {
    val connection = appContainer().connection
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
        }
        io.github.tianli0.switchtether.ui.common.LoadableContent(content, onRetry = { reload++ }) { file ->
            TextFileBody(file, Modifier.weight(1f), onOpenPath = onOpenPath)
        }
    }
}

@Composable
private fun SaveRow(save: SaveState, onManage: (() -> Unit)?, onOpen: (DownloadRecord) -> Unit) {
    if (!save.running && save.error == null && save.record == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        when {
            save.running -> {
                Text(stringResource(R.string.viewer_saving, Formatters.bytes(save.done) + (save.total?.let { " / " + Formatters.bytes(it) } ?: "")), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurface)
                LinearProgressIndicator(progress = save.total?.let { if (it > 0) save.done.toFloat() / it else null }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            save.error != null -> Text(save.error, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger)
            save.record != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.viewer_saved_to, io.github.tianli0.switchtether.files.FileStore.DOWNLOAD_FOLDER), style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.ok, modifier = Modifier.weight(1f))
                TextButton(text = stringResource(R.string.downloads_open), onClick = { onOpen(save.record) })
                if (onManage != null) TextButton(text = stringResource(R.string.viewer_manage), onClick = onManage)
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
        SeekBar(position, duration) { player.seekTo(it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
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

@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var widthPx by remember { mutableIntStateOf(1) }
    Box(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp)
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onSeek((o.x / widthPx * duration).toLong().coerceIn(0, duration)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures { change, _ ->
                    if (duration > 0) onSeek((change.position.x / widthPx * duration).toLong().coerceIn(0, duration))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)).onSizeChanged { widthPx = it.width.coerceAtLeast(1) })
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(COUITheme.colorScheme.primary, RoundedCornerShape(2.dp)))
    }
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
