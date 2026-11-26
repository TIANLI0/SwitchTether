package io.github.tianli0.switchtether.ui.files

import android.graphics.ImageDecoder
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.files.FileStore
import io.github.tianli0.switchtether.files.LocalFile
import io.github.tianli0.switchtether.ui.icons.AppIcons
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * One picture of a lightbox. It comes from bytes the app already holds (a chat attachment), a file
 * on the computer ([path], loaded with the lightbox's `loadBytes`), or a local copy ([local]).
 */
data class LightboxImage(
    val name: String,
    val bytes: ByteArray? = null,
    val path: String? = null,
    val local: LocalFile? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is LightboxImage && other.name == name && other.path == path && other.local == local && other.bytes === bytes

    override fun hashCode(): Int = (name.hashCode() * 31 + (path?.hashCode() ?: 0)) * 31 + (local?.hashCode() ?: 0)
}

/**
 * The full-screen picture viewer (a lightbox): black behind the picture, pinch / double-tap zoom and
 * pan (Telephoto), swipe sideways between the pictures of one message, pull down to close (the
 * black fades with the pull). A tap shows or hides the floating controls — close, name and position
 * at the top; share, save and open-with at the bottom — which also hide by themselves after a while.
 * The controls are the over-media style of COUI's media bar: white on translucent dark, whatever the
 * app theme, because they always sit on a picture or on black.
 */
@Composable
fun ImageLightbox(
    images: List<LightboxImage>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    loadBytes: (suspend (String) -> ByteArray?)? = null,
) {
    if (images.isEmpty()) return
    io.github.tianli0.switchtether.ui.common.AppFullScreenDialog(onDismissRequest = onDismiss) {
        LightboxContent(images, startIndex.coerceIn(0, images.lastIndex), onDismiss, loadBytes)
    }
}

@OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)
@Composable
internal fun LightboxContent(
    images: List<LightboxImage>,
    startIndex: Int,
    onDismiss: () -> Unit,
    loadBytes: (suspend (String) -> ByteArray?)?,
) {
    // Absent where no app container exists (previews): the picture still shows, without the actions.
    val app = LocalContext.current.applicationContext as? io.github.tianli0.switchtether.SwitchTetherApp
    val store: FileStore? = app?.container?.fileStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pager = rememberPagerState(initialPage = startIndex) { images.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    // Immersive like the system gallery: light bar icons on black, and the status / navigation
    // bars leave with the controls (a tap, or the 4 s timeout) so the picture has the whole
    // screen; an edge swipe still shows them for a moment.
    LightSystemBars(barsVisible = chrome)
    val loaded = remember { mutableStateMapOf<Int, ByteArray>() }
    // Telephoto's flick-to-dismiss: the picture follows the finger with rubber banding and is
    // thrown off screen (or springs back) on release by distance and velocity.
    val flick = rememberFlickToDismissState(dismissThresholdRatio = 0.12f, rotateOnDrag = false)
    val flickGesture = flick.gestureState
    LaunchedEffect(flickGesture) {
        when (flickGesture) {
            is FlickToDismissState.GestureState.Dragging -> chrome = false
            // Leave while the picture is still flying out, as the library suggests.
            is FlickToDismissState.GestureState.Dismissing -> {
                delay(flickGesture.animationDuration / 2)
                onDismiss()
            }
            else -> Unit
        }
    }
    // Controls step aside after a few seconds of looking; any tap brings them back.
    LaunchedEffect(chrome, pager.currentPage) {
        if (chrome) {
            delay(4_000)
            chrome = false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("image-lightbox")) {
        val pull = (flick.offsetFraction * 2f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - 0.75f * pull)))
        FlickToDismiss(state = flick, enabled = !zoomed, modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            LightboxPage(
                image = images[index],
                loadBytes = loadBytes,
                onLoaded = { loaded[index] = it },
                onTap = { chrome = !chrome },
                onZoomed = { if (index == pager.currentPage) zoomed = it },
            )
        }
        }
        val current = images[pager.currentPage]
        AnimatedVisibility(
            visible = chrome && pull < 0.01f,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverMediaKey(AppIcons.Close, stringResource(R.string.action_close), onDismiss)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.name, style = COUITheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    if (images.size > 1) {
                        Text(stringResource(R.string.viewer_pdf_page, pager.currentPage + 1, images.size), style = COUITheme.textStyles.footnote1, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
        if (store != null) {
            AnimatedVisibility(
                visible = chrome && pull < 0.01f,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val noApp = stringResource(R.string.viewer_no_app)
                val savedTo = stringResource(R.string.viewer_saved_to, FileStore.DOWNLOAD_FOLDER)
                val notReady = stringResource(R.string.state_loading)
                val act: (suspend () -> Unit) -> Unit = { block ->
                    scope.launch {
                        try {
                            block()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: noApp, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val localCopy: suspend () -> LocalFile? = {
                    val bytes = current.bytes ?: loaded[pager.currentPage]
                    when {
                        current.local != null -> current.local
                        current.path != null -> store.resolve(current.path)
                        bytes != null -> store.cacheBytes(current.name, bytes)
                        else -> null
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .navigationBarsPadding().padding(top = 32.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                ) {
                    OverMediaAction(AppIcons.Share, stringResource(R.string.viewer_share)) {
                        act { localCopy()?.let { startSafely(context, store.shareIntent(it), noApp) } ?: Toast.makeText(context, notReady, Toast.LENGTH_SHORT).show() }
                    }
                    if (current.local?.fromDownload != true) {
                        OverMediaAction(AppIcons.Download, stringResource(R.string.lightbox_save)) {
                            act {
                                val bytes = current.bytes ?: loaded[pager.currentPage]
                                when {
                                    current.path != null -> store.download(current.path)
                                    bytes != null -> store.saveBytes(current.name, bytes)
                                    else -> null
                                }?.let { Toast.makeText(context, savedTo, Toast.LENGTH_SHORT).show() }
                                    ?: Toast.makeText(context, notReady, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    OverMediaAction(AppIcons.OpenWith, stringResource(R.string.lightbox_open_with)) {
                        act { localCopy()?.let { startSafely(context, store.viewIntent(it), noApp) } ?: Toast.makeText(context, notReady, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightboxPage(
    image: LightboxImage,
    loadBytes: (suspend (String) -> ByteArray?)?,
    onLoaded: (ByteArray) -> Unit,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Result<ImageBitmap>?>(null, image) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = when {
                    image.bytes != null -> ImageDecoder.createSource(java.nio.ByteBuffer.wrap(image.bytes))
                    image.local != null -> ImageDecoder.createSource(context.contentResolver, image.local.uri)
                    image.path != null && loadBytes != null -> {
                        val b = loadBytes(image.path) ?: error("not loaded")
                        withContext(Dispatchers.Main) { onLoaded(b) }
                        ImageDecoder.createSource(java.nio.ByteBuffer.wrap(b))
                    }
                    else -> error("no source")
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longSide = maxOf(info.size.width, info.size.height)
                    if (longSide > LIGHTBOX_MAX_DIM) decoder.setTargetSampleSize((longSide + LIGHTBOX_MAX_DIM - 1) / LIGHTBOX_MAX_DIM)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }
        }
    }
    val zoom = rememberZoomableState(zoomSpec = PictureZoomSpec)
    val bmp = bitmap?.getOrNull()
    // The same rule as the Image's ContentScale.Fit. It was "scaled inside" (never up): a picture
    // smaller than the screen (a 720px screenshot on a 1440px phone) was drawn 2x by the Image while
    // the zoom state measured it at 1x, so it opened looking zoomed and cropped, and panned wrongly.
    LaunchedEffect(bmp) {
        if (bmp != null) zoom.setContentLocation(ZoomableContentLocation.scaledToFitAndCenterAligned(Size(bmp.width.toFloat(), bmp.height.toFloat())))
    }
    LaunchedEffect(zoom) {
        snapshotFlow { (zoom.zoomFraction ?: 0f) > 0.01f }.collect(onZoomed)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            bitmap == null -> LightboxMessage(stringResource(R.string.state_loading))
            bmp == null -> LightboxMessage(stringResource(R.string.viewer_image_failed))
            else -> Image(
                bitmap = bmp,
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() }, onDoubleClick = PictureDoubleTap),
            )
        }
    }
}

private const val LIGHTBOX_MAX_DIM = 4096

@Composable
private fun LightboxMessage(text: String) {
    Text(text, style = COUITheme.textStyles.body2, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
}

/** A 44dp round key in the over-media style: white glyph on translucent dark, a light rim. */
@Composable
private fun OverMediaKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/** A key with its name under it, for the lightbox's bottom row. */
@Composable
private fun OverMediaAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OverMediaKey(icon, label, onClick)
        Text(label, style = COUITheme.textStyles.footnote2, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp))
    }
}

/** Light status and navigation bar icons while the lightbox is open (it is always dark). */
@Composable
private fun LightSystemBars(barsVisible: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(controller) {
        val wasLightStatus = controller?.isAppearanceLightStatusBars
        val wasLightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            wasLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            wasLightNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    androidx.compose.runtime.LaunchedEffect(controller, barsVisible) {
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (barsVisible) controller?.show(bars) else controller?.hide(bars)
    }
}

/** A file name for bytes that have none (a pasted or attached picture), by their first bytes. */
fun imageNameFor(bytes: ByteArray?, fallbackBase: String = "image"): String {
    val ext = when {
        bytes == null || bytes.size < 12 -> "png"
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "gif"
        bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
        else -> "png"
    }
    return "$fallbackBase.$ext"
}
