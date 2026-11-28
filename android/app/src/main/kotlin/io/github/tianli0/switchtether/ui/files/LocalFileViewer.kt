package io.github.tianli0.switchtether.ui.files

import android.content.Context
import androidx.compose.foundation.background
import io.github.tianli0.coui17.patterns.LocalPageTopInset
import io.github.tianli0.coui17.patterns.pageScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.files.FileKind
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.files.LocalFile
import io.github.tianli0.switchtether.remote.protocol.FileContent
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file already on this phone (a download, or a preview copy from the cache), opened inside the
 * app: pictures in the lightbox, PDF / video / audio in their viewers, text and code in the code
 * viewer (read from the local copy, so it works offline), anything else as a card that hands it to
 * another app. Downloads used to open only media here and sent text files straight to other apps;
 * cached copies could not be opened at all.
 */
@Composable
fun LocalFileViewer(
    file: LocalFile,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Keep in Downloads" for a cached copy; null for a download. */
    onSave: (() -> Unit)? = null,
) {
    val kind = FileTypes.kindOf(file.name)
    if (kind == FileKind.IMAGE) {
        ImageLightbox(listOf(LightboxImage(name = file.name, local = file)), onDismiss = onBack)
        return
    }
    val store = appContainer().fileStore
    val context = LocalContext.current
    val noApp = stringResource(R.string.viewer_no_app)
    val textLike = kind == FileKind.TEXT || (kind == FileKind.OTHER && FileTypes.extension(file.name).isEmpty())
    io.github.tianli0.switchtether.ui.common.ProgressivePageLayout(modifier, immersive = kind != FileKind.VIDEO, topBar = { _ ->
        FilePreviewHeader(file.name, subtitle, onBack) {
            ToolbarIcon(AppIcons.Share, stringResource(R.string.viewer_share), { startSafely(context, store.shareIntent(file), noApp) })
            ToolbarIcon(AppIcons.OpenWith, stringResource(R.string.viewer_open_with), { startSafely(context, store.viewIntent(file), noApp) })
            if (onSave != null) ToolbarIcon(AppIcons.Download, stringResource(R.string.lightbox_save), onSave)
        }
    }) {
        Box(Modifier.weight(1f).fillMaxWidth().then(if (kind == FileKind.VIDEO) Modifier.background(Color.Black) else Modifier)) {
            when {
                kind.isMedia -> LocalFileBody(file, kind, Modifier.fillMaxSize())
                textLike -> {
                    val content by produceState<Loadable<FileContent>>(Loadable.Loading, file.uri) {
                        value = io.github.tianli0.switchtether.ui.common.loadable { readLocalText(context, file) }
                    }
                    io.github.tianli0.switchtether.ui.common.LoadableContent(content, onRetry = {}) { c ->
                        TextFileBody(c, Modifier.fillMaxSize(), onOpenBinary = { startSafely(context, store.viewIntent(file), noApp) })
                    }
                }
                else -> Box(Modifier.fillMaxSize().pageScroll().verticalScroll(rememberScrollState()).padding(top = LocalPageTopInset.current)) {
                    FileHandOffCard(
                        name = file.name,
                        detail = listOfNotNull(Formatters.bytes(file.size), FileTypes.extension(file.name).takeIf { it.isNotEmpty() }?.uppercase()).joinToString(" · "),
                        message = stringResource(R.string.viewer_no_viewer_local),
                        primary = stringResource(R.string.viewer_open_with) to { startSafely(context, store.viewIntent(file), noApp) },
                        secondary = stringResource(R.string.viewer_share) to { startSafely(context, store.shareIntent(file), noApp) },
                    )
                }
            }
        }
    }
}

/** The beginning of a local text file as the text viewer takes it ([TEXT_READ_LIMIT]); a binary file gives `text = null`. */
internal suspend fun readLocalText(context: Context, file: LocalFile): FileContent = withContext(Dispatchers.IO) {
    val limit = TEXT_READ_LIMIT.toInt()
    val bytes = context.contentResolver.openInputStream(file.uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: error("cannot read ${file.name}")
    val binary = bytes.take(8192).any { it == 0.toByte() }
    FileContent(
        path = file.name,
        text = if (binary) null else bytes.toString(Charsets.UTF_8).removePrefix("﻿"),
        size = file.size,
        truncated = file.size > bytes.size,
    )
}
