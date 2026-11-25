package io.github.tianli0.switchtether.ui.workspace

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.BackArrow
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.coui17.patterns.ErrorState
import io.github.tianli0.coui17.patterns.FormDialog
import io.github.tianli0.coui17.patterns.GlassMenu
import io.github.tianli0.coui17.patterns.LoadingState
import io.github.tianli0.coui17.patterns.LocalIsDarkTheme
import io.github.tianli0.coui17.patterns.MenuEntry
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.coui17.patterns.pageContainerColor
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.forward.DshWebUi
import io.github.tianli0.switchtether.forward.DshWebUrls
import io.github.tianli0.switchtether.forward.DshWebViewModel
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.icons.AppIcons

/**
 * DeepSeek Harness Web (docs/ANDROID_UI.md §31): `dsh web` on the computer, through a port forward,
 * in a WebView. Before it is ready the page shows the state (checking, confirming, starting, not
 * installed, …); then the WebView fills the page under a compact title bar, or the whole screen
 * with the status bar painted in the page's own colour.
 */
@Composable
fun DshWebScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = containerViewModel { c -> DshWebViewModel(c.connection, c.stepUpGate, { c.pairingStoreStepUpEnabled() }, c.portForwards) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (val s = ui) {
        is DshWebUi.Ready -> DshWebBrowser(s, onBack, onStop = vm::stopServer, onReload = vm::check, viaRelay = container.portForwards.active.collectAsStateWithLifecycle().value.firstOrNull { it.leaseId == s.leaseId }?.viaRelay == true)
        else -> DshWebStatePage(s, onBack, onOpen = vm::open, onRetry = vm::check)
    }
}

@Composable
private fun DshWebStatePage(ui: DshWebUi, onBack: () -> Unit, onOpen: () -> Unit, onRetry: () -> Unit) {
    Page(
        title = stringResource(R.string.dsh_web_title),
        onBack = onBack,
        large = false,
        actions = { ToolbarIcon(AppIcons.Refresh, stringResource(R.string.action_refresh), onRetry) },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (ui) {
                DshWebUi.Checking -> LoadingState(stringResource(R.string.dsh_web_checking))
                DshWebUi.Confirming -> LoadingState(stringResource(R.string.dsh_web_confirming))
                is DshWebUi.Starting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingState(stringResource(R.string.dsh_web_starting), Modifier.height(160.dp))
                    Hint(stringResource(R.string.dsh_web_starting_detail))
                }
                DshWebUi.Offline -> ErrorState(stringResource(R.string.dsh_web_offline), retryLabel = stringResource(R.string.action_retry), onRetry = onRetry)
                DshWebUi.NotInstalled -> ErrorState(
                    stringResource(R.string.dsh_web_not_installed),
                    message = stringResource(R.string.dsh_web_not_installed_detail),
                    retryLabel = stringResource(R.string.action_retry),
                    onRetry = onRetry,
                )
                DshWebUi.NoStepUpKey -> ErrorState(stringResource(R.string.dsh_web_failed), message = stringResource(R.string.dsh_web_no_stepup))
                is DshWebUi.Failed -> ErrorState(
                    stringResource(R.string.dsh_web_failed),
                    message = ui.message,
                    retryLabel = stringResource(R.string.action_retry),
                    onRetry = onOpen,
                )
                is DshWebUi.Idle -> {
                    val st = ui.status
                    val detail = listOfNotNull(
                        if (st?.running == true) stringResource(R.string.dsh_web_idle_running, st.port ?: 0) else stringResource(R.string.dsh_web_idle_stopped),
                        ui.error,
                    ).joinToString("\n\n")
                    ErrorState(stringResource(R.string.dsh_web_idle), message = detail, retryLabel = stringResource(R.string.dsh_web_open), onRetry = onOpen)
                }
                is DshWebUi.Ready -> Unit
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = COUITheme.textStyles.footnote1,
        color = COUITheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

/** What the WebView reports, for the chrome around it. */
@Stable
private class WebPageState {
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var authRequired by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    /** The page's own colour at its top (for the status bar in full screen). */
    var pageColor by mutableStateOf<Int?>(null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DshWebBrowser(ready: DshWebUi.Ready, onBack: () -> Unit, onStop: () -> Unit, onReload: () -> Unit, viaRelay: Boolean) {
    val context = LocalContext.current
    val page = remember { WebPageState() }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var signIn by remember { mutableStateOf(false) }
    val localPort = ready.localPort
    val origin = DshWebUrls.origin(localPort)
    val exitHint = stringResource(R.string.dsh_web_exit_full_screen)

    // <input type="file">: the system picker; the callback must always be answered.
    var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else listOfNotNull(data.data).toTypedArray()
        } else {
            null
        }
        pendingFiles?.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
        pendingFiles = null
    }
    val launchPicker by rememberUpdatedState { cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams ->
        pendingFiles?.onReceiveValue(null)
        pendingFiles = cb
        val intent = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { picker.launch(intent) }.onFailure {
            pendingFiles?.onReceiveValue(null)
            pendingFiles = null
        }
    }

    val webView = remember(localPort) { createWebView(context, localPort, page, { cb, p -> launchPicker(cb, p) }) }
    DisposableEffect(webView) {
        onDispose {
            pendingFiles?.onReceiveValue(null)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(ready.generation) {
        page.authRequired = false
        page.loadError = null
        webView.loadUrl(ready.startUrl)
    }
    // Back: the page's own history first, then out of full screen, then leave.
    BackHandler(enabled = page.canGoBack || fullScreen) {
        if (page.canGoBack) webView.goBack() else fullScreen = false
    }
    if (fullScreen) PageColoredSystemBars(page.pageColor)

    val pageColor = pageContainerColor()
    val chromeColor = if (fullScreen) page.pageColor?.let { Color(it) } ?: pageColor else pageColor
    Column(Modifier.fillMaxSize().background(chromeColor)) {
        if (!fullScreen) {
            SmallTopAppBar(
                title = stringResource(R.string.dsh_web_title),
                subtitle = stringResource(if (viaRelay) R.string.dsh_web_subtitle_relay else R.string.dsh_web_subtitle, localPort, ready.remotePort),
                color = pageColor,
                showDivider = false,
                navigationIcon = { BackArrow(onBack) },
                actions = {
                    ToolbarIcon(AppIcons.Refresh, stringResource(R.string.dsh_web_reload), {
                        page.loadError = null
                        webView.reload()
                    })
                    Box {
                        ToolbarIcon(AppIcons.More, stringResource(R.string.dsh_web_more), { menu = true })
                        GlassMenu(
                            show = menu,
                            entries = listOfNotNull(
                                MenuEntry(stringResource(R.string.dsh_web_full_screen)) {
                                    fullScreen = true
                                    Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
                                },
                                MenuEntry(stringResource(R.string.dsh_web_open_in_browser)) { openExternal(context, webView.url ?: "$origin/") },
                                MenuEntry(stringResource(R.string.dsh_web_copy_link)) { copyText(context, webView.url ?: "$origin/") },
                                MenuEntry(stringResource(R.string.dsh_web_sign_in)) { signIn = true },
                                if (ready.owned) MenuEntry(stringResource(R.string.dsh_web_stop), danger = true) { confirmStop = true } else null,
                            ),
                            onDismiss = { menu = false },
                        )
                    }
                },
            )
        } else {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        }
        // Progress while a page loads; a hairline of the same height otherwise keeps the layout still.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (page.progress in 1..99) LinearProgressIndicator(Modifier.fillMaxWidth(), progress = page.progress / 100f, height = 2.dp)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
            val err = page.loadError
            when {
                page.authRequired -> Box(Modifier.fillMaxSize().background(pageColor), contentAlignment = Alignment.Center) {
                    ErrorState(
                        stringResource(R.string.dsh_web_sign_in),
                        message = stringResource(R.string.dsh_web_sign_in_detail),
                        retryLabel = stringResource(R.string.dsh_web_sign_in_action),
                        onRetry = { signIn = true },
                    )
                }
                err != null -> Box(Modifier.fillMaxSize().background(pageColor), contentAlignment = Alignment.Center) {
                    ErrorState(
                        stringResource(R.string.dsh_web_load_error),
                        message = err,
                        retryLabel = stringResource(R.string.action_retry),
                        onRetry = {
                            page.loadError = null
                            if (webView.url.isNullOrBlank()) onReload() else webView.reload()
                        },
                    )
                }
            }
        }
        // Keyboard and gesture bar: the page shrinks above them (the window draws edge to edge).
        Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }

    if (signIn) {
        SignInDialog(
            onDismiss = { signIn = false },
            onToken = { token ->
                signIn = false
                page.authRequired = false
                webView.loadUrl("$origin/?token=$token")
            },
        )
    }
    ConfirmDialog(
        show = confirmStop,
        title = stringResource(R.string.dsh_web_stop),
        message = stringResource(R.string.dsh_web_stop_confirm),
        confirmText = stringResource(R.string.dsh_web_stop),
        onConfirm = {
            confirmStop = false
            onStop()
        },
        onDismiss = { confirmStop = false },
    )
}

/** Full screen: the status / gesture bar icons follow the page's colour; the theme's come back after. */
@Composable
private fun PageColoredSystemBars(color: Int?) {
    val view = LocalView.current
    val dark = LocalIsDarkTheme.current
    DisposableEffect(view, color, dark) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val light = color?.let(DshWebUrls::wantsDarkIcons) ?: !dark
        controller?.isAppearanceLightStatusBars = light
        controller?.isAppearanceLightNavigationBars = light
        onDispose {
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
private fun SignInDialog(onDismiss: () -> Unit, onToken: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val token = DshWebUrls.tokenOf(text)
    FormDialog(
        show = true,
        title = stringResource(R.string.dsh_web_sign_in),
        confirmText = stringResource(R.string.dsh_web_sign_in_action),
        confirmEnabled = token != null,
        onConfirm = { token?.let(onToken) },
        onDismiss = onDismiss,
    ) {
        TextInput(text, { text = it.take(600) }, stringResource(R.string.dsh_web_sign_in_field), singleLine = false, maxLines = 4)
        Text(
            if (text.isNotBlank() && token == null) stringResource(R.string.dsh_web_sign_in_invalid) else stringResource(R.string.dsh_web_sign_in_detail),
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, context.getString(R.string.dsh_web_no_app), Toast.LENGTH_SHORT).show() }
}

private fun copyText(context: Context, text: String) {
    // Never the sign-in link: the page's address after dsh's redirect carries no token.
    val safe = if (text.contains("token=")) text.substringBefore('?') else text
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("URL", safe))
    Toast.makeText(context, context.getString(R.string.forward_copied), Toast.LENGTH_SHORT).show()
}

/**
 * A WebView limited to `http://localhost:<localPort>`: JavaScript and DOM storage (dsh web is a
 * single-page app) but no file / content URLs, no geolocation, camera or microphone, no extra
 * windows; links elsewhere go to the phone's browser.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    localPort: Int,
    page: WebPageState,
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        @Suppress("DEPRECATION")
        saveFormData = false
        builtInZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    setDownloadListener { _, _, _, _, _ ->
        Toast.makeText(context, context.getString(R.string.dsh_web_no_downloads), Toast.LENGTH_SHORT).show()
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (DshWebUrls.isSameOrigin(url, localPort)) return false
            // Leaving the forwarded origin: the phone's browser for web links (top-level only),
            // nothing for other schemes (intent:, file:, javascript:, …).
            if (request.isForMainFrame && DshWebUrls.isExternalWeb(url)) openExternal(context, url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (DshWebUrls.isSameOrigin(url, localPort)) page.loadError = null
        }

        override fun onPageFinished(view: WebView, url: String?) {
            page.canGoBack = view.canGoBack()
            view.evaluateJavascript(DshWebUrls.PAGE_COLOR_JS) { v -> DshWebUrls.parseCssColor(v)?.let { page.pageColor = it } }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            page.canGoBack = view.canGoBack()
            // dsh web routes inside the page (history API): its colour may change with the view.
            view.evaluateJavascript(DshWebUrls.PAGE_COLOR_JS) { v -> DshWebUrls.parseCssColor(v)?.let { page.pageColor = it } }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode == 401) page.authRequired = true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) page.loadError = error.description?.toString() ?: "error ${error.errorCode}"
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            page.progress = newProgress
            if (newProgress == 100) page.canGoBack = view.canGoBack()
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            onFileChooser(callback, params)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
    }
}
