package io.github.tianli0.switchtether.ui

import io.github.tianli0.switchtether.ui.icons.AppIcons

import io.github.tianli0.switchtether.ui.nav.AppNavHost
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
import androidx.compose.ui.graphics.graphicsLayer
import io.github.suqi8.coui.kmp.kit.motion.couiScaleVisibility
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.suqi8.coui.kmp.basic.NavigationRail
import io.github.suqi8.coui.kmp.basic.NavigationRailItem
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.AppContainer
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.SwitchTetherApp
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.coui17.patterns.LocalBottomBarSpace
import io.github.tianli0.coui17.patterns.pageContainerColor
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
    val predictiveBack by container.settingsStore.predictiveBack.collectAsStateWithLifecycle(true)
    SwitchTetherTheme(theme) {
      // coui's default sheet close capsule label (patch 0002) follows the app locale.
      CompositionLocalProvider(
        io.github.suqi8.coui.kmp.layout.LocalSheetCloseLabel provides stringResource(R.string.action_close),
        io.github.tianli0.coui17.patterns.LocalExpandStateLabels provides (stringResource(R.string.state_expanded) to stringResource(R.string.state_collapsed)),
        // Long page titles (session names) scroll instead of being cut (coui patch 0004).
        io.github.suqi8.coui.kmp.basic.LocalTopAppBarTitleMarquee provides true,
        io.github.tianli0.switchtether.ui.code.LocalCodeCopyLabel provides stringResource(R.string.action_copy),
        io.github.tianli0.switchtether.ui.code.LocalCodeWrapLabels provides (stringResource(R.string.command_wrap) to stringResource(R.string.command_scroll)),
        io.github.tianli0.switchtether.domain.LocalMoney provides container.money.display.collectAsState().value,
        io.github.tianli0.switchtether.ui.common.LocalPredictiveBack provides predictiveBack,
      ) {
        io.github.tianli0.switchtether.ui.common.LocalNetworkPermissionHost()
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
            // "Share to SwitchTether": the pair-first explanation and keeping a waiting share readable.
            if (desktop !== LOADING_SENTINEL) io.github.tianli0.switchtether.share.ShareRootEffects(paired = desktop != null, verificationPending = pending != null)
        }
      }
    }
}

private val LOADING_SENTINEL = io.github.tianli0.switchtether.data.PairedDesktop("", "", "", emptyList(), null, 0, "", "", null, false, 0)

@Composable
fun MainShell() {
    val nav: NavViewModel = viewModel()
    val container = appContainer()
    io.github.tianli0.switchtether.ui.settings.UpdateHost()
    io.github.tianli0.switchtether.ui.common.PinExplainSheet()
    // First LAN connect on Android 17: explain and ask once for the local network permission.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val access = container.localNetwork
        val d = container.pairingStore.current()
        if (access.required && !access.isGranted() && !access.rationaleShown &&
            d != null && d.hosts.any(io.github.tianli0.switchtether.remote.pairing.LocalNetwork::isLocal)
        ) {
            io.github.tianli0.switchtether.ui.common.localNetworkAsk.value = io.github.tianli0.switchtether.ui.common.LocalNetworkAsk()
        }
    }
    val openRequest by container.openSessionRequest.collectAsState()
    val openTerminal by container.openTerminalRequest.collectAsState()
    // Switch to Workspace; WorkspaceScreen consumes the request and attaches (with step-up).
    androidx.compose.runtime.LaunchedEffect(openTerminal) {
        if (openTerminal != null) nav.select(Tab.WORKSPACE)
    }
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
        // Pairing saves the computer a moment before it hands over the 6-digit code to compare, so
        // the main shell is composed for a frame in between: without this pause the system prompt
        // opened over the code. If the code step takes over, this shell leaves and the wait is
        // cancelled; the prompt then comes once the user has compared the codes.
        kotlinx.coroutines.delay(800)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    // Back (incl. the predictive gesture) is handled by AppNavHost.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val twoPanes = io.github.tianli0.switchtether.ui.nav.usesTwoPanes(maxWidth)
        val top = nav.top
        val newSessionLabel = stringResource(R.string.new_session_title)
        val searchLabel = stringResource(R.string.sessions_search)
        var showNewSession by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        io.github.tianli0.switchtether.ui.sessions.NewSessionSheet(
            show = showNewSession,
            onDismiss = {
                showNewSession = false
                container.shareInbox.newSessionClosed()
            },
            onPickFolder = {
                showNewSession = false
                nav.push(Route.PickFolder)
            },
            onOpenDshWeb = {
                showNewSession = false
                nav.push(Route.DshWeb)
            },
            onCreated = { id, title ->
                // "从手机发起的任务自动钉住": the phone started it, so following it is user-initiated.
                if (container.liveUpdates.autoPinPhoneSessions) {
                    container.liveUpdates.pin(io.github.tianli0.switchtether.remote.protocol.SessionSummary(id = id, title = title, liveness = "live", status = "busy", managed = true), manual = false)
                }
                // A share that chose "new session" brings its files into this session's input box.
                container.shareInbox.sessionCreated(id)
                nav.push(Route.SessionDetail(id, title))
            },
        )
        io.github.tianli0.switchtether.share.ShareHost(nav)
        // Hardware keyboard (§35): search, new session, tabs, and the neighbouring session while the
        // list stands beside the conversation. The session page on top answers its own keys first.
        val shortcuts = io.github.tianli0.switchtether.ui.keys.LocalShortcuts.current
        io.github.tianli0.switchtether.ui.keys.ShortcutHandler { action ->
            val order = shortcuts?.sessionOrder.orEmpty()
            val selected = nav.stack.firstNotNullOfOrNull { (it as? Route.SessionDetail)?.sessionId }
            val shell = io.github.tianli0.switchtether.ui.keys.ShellKeyState(twoPanes, order.map { it.first }, selected)
            if (!shell.allows(action)) return@ShortcutHandler false
            when (action) {
                io.github.tianli0.switchtether.ui.keys.ShortcutAction.SEARCH -> nav.openSessionsSearch()
                io.github.tianli0.switchtether.ui.keys.ShortcutAction.NEW_SESSION -> showNewSession = true
                io.github.tianli0.switchtether.ui.keys.ShortcutAction.TAB_HOME -> nav.select(Tab.HOME)
                io.github.tianli0.switchtether.ui.keys.ShortcutAction.TAB_SESSIONS -> nav.select(Tab.SESSIONS)
                io.github.tianli0.switchtether.ui.keys.ShortcutAction.TAB_WORKSPACE -> nav.select(Tab.WORKSPACE)
                io.github.tianli0.switchtether.ui.keys.ShortcutAction.PREVIOUS_SESSION, io.github.tianli0.switchtether.ui.keys.ShortcutAction.NEXT_SESSION -> {
                    val id = shell.neighbour(if (action == io.github.tianli0.switchtether.ui.keys.ShortcutAction.NEXT_SESSION) 1 else -1) ?: return@ShortcutHandler false
                    nav.openFromList(Route.SessionDetail(id, order.firstOrNull { it.first == id }?.second))
                }
                else -> return@ShortcutHandler false
            }
            true
        }
        val reopen by container.newSessionReopen.collectAsState()
        androidx.compose.runtime.LaunchedEffect(reopen) {
            if (reopen) {
                container.newSessionReopen.value = false
                showNewSession = true
            }
        }
        val items = listOf(
            NavItem(Tab.HOME, stringResource(R.string.tab_home), AppIcons.Home, AppIcons.HomeSelected),
            NavItem(Tab.SESSIONS, stringResource(R.string.tab_sessions), AppIcons.Sessions, AppIcons.SessionsSelected),
            NavItem(Tab.WORKSPACE, stringResource(R.string.tab_workspace), AppIcons.Workspace, AppIcons.WorkspaceSelected),
        )
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.statusBarsPadding(), color = pageContainerColor()) {
                    items.forEach { item ->
                        val selected = nav.tab == item.tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { nav.select(item.tab) },
                            icon = if (selected) item.selectedIcon else item.icon,
                            label = item.label,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    // Tablets / unfolded foldables: the tab's list stays beside what it opened (§27).
                    if (twoPanes) TwoPaneHost(nav)
                    else AppNavHost(nav, root = { Destination(nav, it, null) }, page = { t, r -> Destination(nav, t, r) })
                }
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
                    // ColorOS 17 materials (frosted title-bar buttons, dialogs, sheets) everywhere. coui17
                    // itself falls back to flat fills below Android 13 or with system blur off.
                    CompositionLocalProvider(
                        LocalBlurEnabled provides true,
                        LocalBottomBarSpace provides if (showBar) 96.dp else 0.dp,
                    ) {
                        AppNavHost(nav, root = { Destination(nav, it, null) }, page = { t, r -> Destination(nav, t, r) })
                    }
                }
                // ColorOS shows and hides the bar in place: scale, alpha and (with system blur on) a
                // foreground blur on COUI springs; it never slides (COUI17 CouiScaleVisibility).
                val barVisibility = io.github.suqi8.coui.kmp.kit.motion.rememberCouiScaleVisibility(showBar)
                if (barVisibility.isPresent) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .couiScaleVisibility(barVisibility)
                            // A hiding bar takes no touches.
                            .then(if (showBar) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }),
                    ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 12.dp),
                    ) {
                        CompositionLocalProvider(
                            LocalBackdrop provides backdrop,
                            LocalBlurEnabled provides true,
                        ) {
                            // Search and New session split out of the capsule on the two list tabs
                            // (Home, Sessions) and melt back into it on Workspace and Manage.
                            val listTab = nav.tab == Tab.HOME || nav.tab == Tab.SESSIONS
                            io.github.suqi8.coui.kmp.basic.BottomFloatingAccessoryNavigationBar(
                                startAccessoryVisible = listTab,
                                endAccessoryVisible = listTab,
                                startAccessory = { BarAccessory(AppIcons.Search, searchLabel, enabled = listTab) { nav.openSessionsSearch() } },
                                endAccessory = { BarAccessory(AppIcons.Add, newSessionLabel, enabled = listTab) { showNewSession = true } },
                            ) {
                                items.forEach { item ->
                                    val selected = nav.tab == item.tab
                                    BottomFloatingNavigationBarItem(
                                        selected = selected,
                                        onClick = { nav.select(item.tab) },
                                        icon = item.icon,
                                        selectedIcon = item.selectedIcon,
                                        label = item.label,
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * List | detail: the current tab's root on the left (cross-fading between tabs, each keeping its
 * scroll position), its stack on the right with the usual push / pop / predictive-back motion; with
 * nothing open the right pane shows a hint. Rows opened from the list replace the detail.
 */
@Composable
private fun TwoPaneHost(nav: NavViewModel) {
    val listStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val selected = nav.stack.firstNotNullOfOrNull { (it as? Route.SessionDetail)?.sessionId }
    CompositionLocalProvider(io.github.tianli0.switchtether.ui.nav.LocalSelectedSessionId provides selected) {
        io.github.tianli0.switchtether.ui.nav.ListDetailPanes(
            list = {
                androidx.compose.animation.Crossfade(nav.tab, animationSpec = androidx.compose.animation.core.tween(150), label = "pane-list") { t ->
                    listStates.SaveableStateProvider("list/${t.name}") {
                        Destination(nav, t, null, onOpenRoot = nav::openFromList)
                    }
                }
            },
            detail = {
                AppNavHost(nav, root = { io.github.tianli0.switchtether.ui.nav.PaneEmptyState(it) }, page = { t, r -> Destination(nav, t, r) })
            },
        )
    }
}

/**
 * One round key that splits out of the floating bar ([io.github.suqi8.coui.kmp.basic.BottomFloatingAccessoryNavigationBar]):
 * the bar draws the glass ball, its press light and spring; this is the icon and the click.
 */
@Composable
private fun BarAccessory(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, interactionSource = null, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = COUITheme.colorScheme.onSurface)
    }
}

/** Outlined icon when unselected, filled icon when selected (Material Icons pairs). */
private class NavItem(val tab: Tab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
private fun Destination(nav: NavViewModel, tab: Tab, route: Route?, onOpenRoot: (Route) -> Unit = nav::push) {
    val onBack: () -> Unit = { nav.back() }
    when (val top = route) {
        is Route.SessionDetail -> {
            val container = appContainer()
            androidx.compose.runtime.LaunchedEffect(top.sessionId) {
                // Seen: its "已完成" result notification has done its job.
                container.liveUpdates.onSessionOpened(top.sessionId)
            }
            // Handed off only while this page is the one on top (the page under a pushed page
            // stays composed, paused).
            androidx.lifecycle.compose.LifecycleResumeEffect(top.sessionId) {
                container.handoffSession.value = top.sessionId
                onPauseOrDispose { if (container.handoffSession.value == top.sessionId) container.handoffSession.value = null }
            }
            // A resumed session takes this page's place, so back does not return to the old read-only view.
            SessionDetailScreen(top.sessionId, top.title, onBack, onOpenSession = { id, title -> nav.replace(Route.SessionDetail(id, title)) }, onOpenRoute = nav::push)
        }
        is Route.Subagent -> io.github.tianli0.switchtether.ui.sessions.SubagentScreen(top.sessionId, top.agentId, top.title, onBack, onOpen = nav::push, agent = top.agent)
        is Route.SubagentTree -> io.github.tianli0.switchtether.ui.sessions.SubagentTreeScreen(top.sessionId, onBack, onOpen = nav::push, agent = top.agent)
        is Route.SessionTools -> io.github.tianli0.switchtether.ui.sessions.SessionToolsScreen(top.sessionId, onBack)
        // Every entry point opens the one launcher sheet (the folder picker returns to it).
        Route.NewSession -> {
            val container = appContainer()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                nav.back()
                container.newSessionReopen.value = true
            }
        }
        is Route.Terminal -> io.github.tianli0.switchtether.ui.workspace.TerminalPage(top.id, onBack)
        Route.Files -> io.github.tianli0.switchtether.ui.workspace.FilesPage(onBack, onOpenDownloads = { nav.push(Route.Downloads) })
        Route.Downloads -> io.github.tianli0.switchtether.ui.files.DownloadsScreen(onBack)
        Route.Git -> io.github.tianli0.switchtether.ui.workspace.GitPage(onBack)
        is Route.GitReview -> io.github.tianli0.switchtether.ui.workspace.GitReviewPage(top.cwd, top.turnFiles, onBack)
        Route.PortForward -> io.github.tianli0.switchtether.ui.workspace.PortForwardScreen(onBack)
        Route.DshWeb -> io.github.tianli0.switchtether.ui.workspace.DshWebScreen(onBack)
        Route.PickFolder -> io.github.tianli0.switchtether.ui.sessions.PickFolderScreen(onBack)
        Route.Manage -> ManageScreen(onOpen = nav::push, onBack = onBack)
        Route.Providers -> ProvidersScreen(onBack)
        Route.Usage -> UsageScreen(onBack, onOpen = nav::push)
        is Route.ProjectUsage -> io.github.tianli0.switchtether.ui.manage.ProjectUsageScreen(top.project, onBack, onOpen = nav::push)
        Route.Mcp -> ToggleListScreen(ToggleListKind.MCP, onBack)
        Route.Skills -> ToggleListScreen(ToggleListKind.SKILLS, onBack)
        Route.Prompts -> ToggleListScreen(ToggleListKind.PROMPTS, onBack)
        Route.NotificationPrefs -> NotificationPrefsScreen(onBack)
        Route.Licenses -> LicensesScreen(onBack)
        Route.About -> io.github.tianli0.switchtether.ui.settings.AboutScreen(onBack, onOpen = nav::push)
        Route.DesktopInfo -> DesktopInfoScreen(onBack)
        null -> when (tab) {
            Tab.HOME -> HomeScreen(onOpen = onOpenRoot, onSelectTab = nav::select)
            Tab.SESSIONS -> SessionsScreen(onOpen = onOpenRoot, searchRequested = nav.sessionsSearchRequested, onSearchHandled = { nav.sessionsSearchRequested = false })
            Tab.WORKSPACE -> WorkspaceScreen(onOpen = onOpenRoot)
            Tab.MANAGE -> ManageScreen(onOpen = onOpenRoot)
        }
    }
}
