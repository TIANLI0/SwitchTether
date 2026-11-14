package io.github.tianli0.switchtether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.blur.LocalBackdrop
import io.github.suqi8.coui.kmp.blur.LocalBlurEnabled
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.icon.extended.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.suqi8.coui.kmp.basic.BottomFloatingNavigationBar
import io.github.suqi8.coui.kmp.basic.BottomFloatingNavigationBarDefaults
import io.github.suqi8.coui.kmp.basic.BottomFloatingNavigationBarItem
import io.github.suqi8.coui.kmp.basic.NavigationRail
import io.github.suqi8.coui.kmp.basic.NavigationRailItem
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Home
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.AppContainer
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.SwitchTetherApp
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.ui.common.LocalBottomBarSpace
import io.github.tianli0.switchtether.ui.common.pageContainerColor
import io.github.tianli0.switchtether.ui.home.HomeScreen
import io.github.tianli0.switchtether.ui.manage.ManageScreen
import io.github.tianli0.switchtether.ui.manage.ProvidersScreen
import io.github.tianli0.switchtether.ui.manage.ToggleListKind
import io.github.tianli0.switchtether.ui.manage.ToggleListScreen
import io.github.tianli0.switchtether.ui.manage.UsageScreen
import io.github.tianli0.switchtether.ui.nav.NavViewModel
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.nav.Tab
import io.github.tianli0.switchtether.ui.onboarding.OnboardingScreen
import io.github.tianli0.switchtether.ui.sessions.NewSessionScreen
import io.github.tianli0.switchtether.ui.sessions.SessionDetailScreen
import io.github.tianli0.switchtether.ui.sessions.SessionsScreen
import io.github.tianli0.switchtether.ui.settings.DesktopInfoScreen
import io.github.tianli0.switchtether.ui.settings.LicensesScreen
import io.github.tianli0.switchtether.ui.settings.NotificationPrefsScreen
import io.github.tianli0.switchtether.ui.settings.SettingsScreen
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import io.github.tianli0.switchtether.ui.workspace.WorkspaceScreen

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as SwitchTetherApp).container

/** ViewModel created from the [AppContainer] (manual DI). */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

@Composable
fun SwitchTetherRoot() {
    val container = appContainer()
    val theme by container.settingsStore.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    SwitchTetherTheme(theme) {
        val desktop by container.pairingStore.desktop.collectAsStateWithLifecycle(initialValue = LOADING_SENTINEL)
        val conn by container.connection.state.collectAsState()
        val pending by container.pendingVerification.collectAsState()
        // Performs step-up requests from ViewModels with this activity (BiometricPrompt).
        val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
        androidx.compose.runtime.LaunchedEffect(activity) {
            if (activity != null) container.stepUpGate.requests.collect { container.stepUpGate.handle(activity, it) }
        }
        // COUI pages sit on `surface` (light grey); cards use `surfaceContainer`.
        Box(Modifier.fillMaxSize().background(pageContainerColor())) {
            when {
                desktop === LOADING_SENTINEL -> Unit // first DataStore read (a few ms): draw background only
                desktop == null || pending != null -> OnboardingScreen(revokedReason = (conn as? ConnectionState.Revoked)?.let { it.reason to it.desktopName })
                else -> MainShell()
            }
        }
    }
}

private val LOADING_SENTINEL = io.github.tianli0.switchtether.data.PairedDesktop("", "", "", emptyList(), null, 0, "", "", null, false, 0)

@Composable
fun MainShell() {
    val nav: NavViewModel = viewModel()
    val container = appContainer()
    val openRequest by container.openSessionRequest.collectAsState()
    androidx.compose.runtime.LaunchedEffect(openRequest) {
        openRequest?.let {
            container.openSessionRequest.value = null
            nav.push(Route.SessionDetail(it, null))
        }
    }
    // Android 13+: ask for notification permission once the user reached the main UI.
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.HOME) { nav.back() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val top = nav.top
        val newSessionLabel = stringResource(R.string.new_session_title)
        var showNewSession by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        io.github.tianli0.switchtether.ui.sessions.NewSessionSheet(
            show = showNewSession,
            onDismiss = { showNewSession = false },
            onCreated = { id, title -> nav.push(Route.SessionDetail(id, title)) },
        )
        val items = listOf(
            Triple(Tab.HOME, stringResource(R.string.tab_home), COUIIcons.Home),
            Triple(Tab.SESSIONS, stringResource(R.string.tab_sessions), COUIIcons.Messages),
            Triple(Tab.WORKSPACE, stringResource(R.string.tab_workspace), COUIIcons.Layers),
            Triple(Tab.MANAGE, stringResource(R.string.tab_manage), COUIIcons.Tune),
            Triple(Tab.SETTINGS, stringResource(R.string.tab_settings), COUIIcons.Settings),
        )
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.statusBarsPadding(), color = pageContainerColor()) {
                    items.forEach { (tab, label, icon) ->
                        NavigationRailItem(selected = nav.tab == tab, onClick = { nav.select(tab) }, icon = icon, label = label)
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) { Destination(nav, top) }
            }
        } else {
            // ColorOS 17 floating glass capsule (coui17 BottomFloatingNavigationBar), wired exactly like
            // the coui17 gallery: the page content is a LayerBackdrop that the capsule samples, the bar
            // is its SIBLING (never inside the recorded layer) and lists scroll underneath it.
            val pageColor = pageContainerColor()
            val backdrop = if (isRuntimeShaderSupported()) {
                rememberLayerBackdrop {
                    drawRect(pageColor)
                    drawContent()
                }
            } else {
                null
            }
            val showBar = top == null
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
                    // Glass only for the capsule: pages and dialogs keep opaque COUI fills.
                    CompositionLocalProvider(
                        LocalBlurEnabled provides false,
                        LocalBottomBarSpace provides if (showBar) 96.dp else 0.dp,
                    ) {
                        Destination(nav, top)
                    }
                }
                AnimatedVisibility(
                    visible = showBar,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 12.dp),
                    ) {
                        CompositionLocalProvider(LocalBackdrop provides backdrop, LocalBlurEnabled provides true) {
                            BottomFloatingNavigationBar(
                                // Primary action of the Sessions tab melts out of the capsule.
                                expanded = nav.tab == Tab.SESSIONS,
                                expansion = {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clickable(role = Role.Button, onClickLabel = newSessionLabel) { showNewSession = true }
                                            .semantics { contentDescription = newSessionLabel },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(COUIIcons.Add, contentDescription = null, tint = COUITheme.colorScheme.onSurface)
                                    }
                                },
                            ) {
                                items.forEach { (tab, label, icon) ->
                                    BottomFloatingNavigationBarItem(selected = nav.tab == tab, onClick = { nav.select(tab) }, icon = icon, label = label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Destination(nav: NavViewModel, top: Route?) {
    val onBack: () -> Unit = { nav.back() }
    when (top) {
        is Route.SessionDetail -> SessionDetailScreen(top.sessionId, top.title, onBack)
        Route.NewSession -> NewSessionScreen(onBack, onCreated = { id, title ->
            nav.back()
            nav.push(Route.SessionDetail(id, title))
        })
        Route.Providers -> ProvidersScreen(onBack)
        Route.Usage -> UsageScreen(onBack)
        Route.Mcp -> ToggleListScreen(ToggleListKind.MCP, onBack)
        Route.Skills -> ToggleListScreen(ToggleListKind.SKILLS, onBack)
        Route.Prompts -> ToggleListScreen(ToggleListKind.PROMPTS, onBack)
        Route.NotificationPrefs -> NotificationPrefsScreen(onBack)
        Route.Licenses -> LicensesScreen(onBack)
        Route.DesktopInfo -> DesktopInfoScreen(onBack)
        null -> when (nav.tab) {
            Tab.HOME -> HomeScreen(onOpen = nav::push, onSelectTab = nav::select)
            Tab.SESSIONS -> SessionsScreen(onOpen = nav::push)
            Tab.WORKSPACE -> WorkspaceScreen()
            Tab.MANAGE -> ManageScreen(onOpen = nav::push)
            Tab.SETTINGS -> SettingsScreen(onOpen = nav::push)
        }
    }
}
