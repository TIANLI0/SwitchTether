package io.github.tianli0.switchtether.ui.settings

import io.github.tianli0.coui17.patterns.PageDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import io.github.tianli0.switchtether.ui.common.ConnectionBanner
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.BuildConfig
import io.github.tianli0.switchtether.R
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.notify.Notifier
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.coui17.patterns.TextInput
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.security.StepUpSignException
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.coui17.patterns.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.switchtether.ui.common.connectionText
import io.github.tianli0.coui17.patterns.KeyValueRow
import io.github.tianli0.coui17.patterns.SectionHeader
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpen: (Route) -> Unit, onBack: (() -> Unit)? = null, manageItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val desktop by container.pairingStore.desktop.collectAsStateWithLifecycle(null)
    val theme by container.settingsStore.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val background by container.settingsStore.backgroundConnection.collectAsStateWithLifecycle(true)
    val conn by container.connection.state.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var stepUpResult by remember { mutableStateOf<String?>(null) }
    var stepUpBusy by remember { mutableStateOf(false) }
    val stepUpScopeConfig = stringResource(R.string.stepup_scope_config_write)
    val stepUpReasonCanceled = stringResource(R.string.stepup_reason_canceled)
    val stepUpReasonNoKey = stringResource(R.string.stepup_reason_no_key)
    val stepUpReasonInvalidated = stringResource(R.string.stepup_reason_key_invalidated)
    val stepUpReasonFailed = stringResource(R.string.stepup_reason_failed)
    val toastContext = LocalContext.current.applicationContext
    val clearedText = stringResource(R.string.settings_clear_cache_done)
    val unpairLocalOnly = stringResource(R.string.settings_unpair_local_only)
    val activity = LocalContext.current as? FragmentActivity
    val stepUpTitle = stringResource(R.string.stepup_prompt_title)
    val stepUpSubtitle = stringResource(R.string.stepup_prompt_subtitle)
    val okFmt = stringResource(R.string.stepup_ok)
    val failFmt = stringResource(R.string.stepup_failed)

    // Landscape: the large collapsing title and the space above it took a third of the height.
    val roomy = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 480
    Page(title = stringResource(R.string.tab_manage), onBack = onBack, immersive = true, large = roomy) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { ConnectionBanner(conn, { container.connection.retryNow() }) }
            item {
                GroupCard {
                    ArrowPreference(
                        title = desktop?.name ?: "—",
                        summary = connectionText(conn),
                        startAction = { Box(Modifier.padding(end = 12.dp).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.animation.animateColorAsState(io.github.tianli0.switchtether.ui.common.connectionColor(conn), label = "conn-dot").value)) },
                        onClick = { onOpen(Route.DesktopInfo) },
                        cardListPosition = CardListPosition.Full,
                    )
                }
            }
            manageItems()
            item { SectionHeader(stringResource(R.string.settings_security)) }
            item {
                GroupCard {
                    val stepUp = desktop?.stepUpEnabled == true
                    ArrowPreference(
                        title = stringResource(R.string.settings_stepup_test),
                        summary = stepUpResult ?: if (stepUp) stringResource(R.string.settings_stepup_summary) else stringResource(R.string.settings_stepup_disabled),
                        // One prompt at a time: the row is disabled while a confirmation runs.
                        enabled = stepUp && conn is ConnectionState.Online && activity != null && !stepUpBusy,
                        cardListPosition = CardListPosition.Full,
                        onClick = {
                            val act = activity ?: return@ArrowPreference
                            if (stepUpBusy) return@ArrowPreference
                            stepUpBusy = true
                            scope.launch {
                                stepUpResult = try {
                                    val grant = container.stepUp(act, StepUpScopes.CONFIG_WRITE, stepUpTitle, stepUpSubtitle)
                                    val scopeLabel = if (grant.scope == StepUpScopes.CONFIG_WRITE) stepUpScopeConfig else grant.scope
                                    val until = Formatters.parseInstant(grant.expiresAt)?.let { Formatters.dateTime(it.toEpochMilli()) } ?: grant.expiresAt
                                    okFmt.format(scopeLabel, until)
                                } catch (e: StepUpSignException) {
                                    failFmt.format(
                                        when (e.reason) {
                                            StepUpSignException.Reason.CANCELED -> stepUpReasonCanceled
                                            StepUpSignException.Reason.NO_KEY -> stepUpReasonNoKey
                                            StepUpSignException.Reason.KEY_INVALIDATED -> stepUpReasonInvalidated
                                            StepUpSignException.Reason.FAILED -> stepUpReasonFailed
                                        },
                                    )
                                } catch (e: Exception) {
                                    failFmt.format(ConnectionManager.describe(e))
                                } finally {
                                    stepUpBusy = false
                                }
                            }
                        },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                GroupCard {
                    val modes = listOf(
                        ThemeMode.SYSTEM to R.string.theme_system,
                        ThemeMode.LIGHT to R.string.theme_light,
                        ThemeMode.DARK to R.string.theme_dark,
                    )
                    modes.forEachIndexed { i, (mode, label) ->
                        RadioButtonPreference(
                            title = stringResource(label),
                            selected = theme == mode,
                            onClick = { scope.launch { container.settingsStore.setThemeMode(mode) } },
                            cardListPosition = cardListPositionOf(i, modes.size),
                        )
                    }
                }
            }
            item {
                val predictive by container.settingsStore.predictiveBack.collectAsStateWithLifecycle(true)
                GroupCard {
                    SwitchPreference(
                        checked = predictive,
                        onCheckedChange = { v -> scope.launch { container.settingsStore.setPredictiveBack(v) } },
                        title = stringResource(R.string.settings_predictive_back),
                        summary = stringResource(R.string.settings_predictive_back_summary),
                        cardListPosition = CardListPosition.Full,
                    )
                }
            }
            // Android 13+: the system's per-app language page (locales_config lists en / zh-CN).
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                item {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    GroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.settings_language),
                            summary = stringResource(R.string.settings_language_summary),
                            cardListPosition = CardListPosition.Full,
                            onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                                            .setData(android.net.Uri.fromParts("package", ctx.packageName, null)),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.settings_connection)) }
            item {
                GroupCard {
                    SwitchPreference(
                        checked = background,
                        onCheckedChange = { v -> container.setBackgroundConnection(v) },
                        title = stringResource(R.string.settings_background),
                        summary = stringResource(R.string.settings_background_summary),
                        cardListPosition = CardListPosition.Head,
                    )
                    CompanionPreference(cardListPosition = CardListPosition.Middle)
                    val ctx = LocalContext.current
                    var unrestricted by remember { mutableStateOf(true) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                        unrestricted = ctx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) != false
                        onPauseOrDispose { }
                    }
                    if (!unrestricted) {
                        // Doze / OEM battery managers cut the connection: finished tasks are announced late.
                        ArrowPreference(
                            title = stringResource(R.string.notif_battery_fix_title),
                            summary = stringResource(R.string.notif_battery_fix_summary),
                            onClick = {
                                runCatching {
                                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)))
                                }.onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                            },
                            cardListPosition = CardListPosition.Middle,
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.settings_notifications),
                        summary = stringResource(R.string.settings_notifications_summary),
                        onClick = { onOpen(Route.NotificationPrefs) },
                        cardListPosition = CardListPosition.Tail,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_storage)) }
            item {
                GroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.downloads_title),
                        summary = stringResource(R.string.downloads_settings_summary),
                        onClick = { onOpen(Route.Downloads) },
                        cardListPosition = CardListPosition.Head,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_clear_cache),
                        summary = stringResource(R.string.settings_clear_cache_summary),
                        onClick = { confirmClear = true },
                        cardListPosition = CardListPosition.Tail,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                GroupCard {
                    val updState by container.updates.state.collectAsStateWithLifecycle()
                    ArrowPreference(
                        title = stringResource(R.string.about_title),
                        summary = updateSummary(updState, BuildConfig.VERSION_NAME),
                        onClick = { onOpen(Route.About) },
                        cardListPosition = CardListPosition.Full,
                        modifier = Modifier.testTag("about"),
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_desktop)) }
            item {
                GroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.settings_unpair),
                        summary = stringResource(R.string.settings_unpair_summary),
                        onClick = { confirmUnpair = true },
                        cardListPosition = CardListPosition.Full,
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp + io.github.tianli0.coui17.patterns.LocalBottomBarSpace.current)) }
        }
    }
    ConfirmDialog(
        show = confirmUnpair,
        title = stringResource(R.string.settings_unpair),
        message = stringResource(R.string.settings_unpair_confirm, desktop?.name ?: ""),
        confirmText = stringResource(R.string.settings_unpair),
        onConfirm = {
            confirmUnpair = false
            scope.launch {
                // Offline: this phone forgets the computer, but the computer still lists the phone.
                if (!container.unpair()) {
                    android.widget.Toast.makeText(toastContext, unpairLocalOnly, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        },
        onDismiss = { confirmUnpair = false },
    )
    ConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.settings_clear_cache),
        message = stringResource(R.string.settings_clear_cache_confirm),
        confirmText = stringResource(R.string.settings_clear_cache),
        onConfirm = {
            confirmClear = false
            container.clearCachesAndDrafts()
            android.widget.Toast.makeText(toastContext, clearedText, android.widget.Toast.LENGTH_SHORT).show()
        },
        onDismiss = { confirmClear = false },
    )
}

@Composable
fun DesktopInfoScreen(onBack: () -> Unit) {
    val container = appContainer()
    val desktop by container.pairingStore.desktop.collectAsStateWithLifecycle(null)
    val conn by container.connection.state.collectAsStateWithLifecycle()
    Page(title = stringResource(R.string.settings_desktop), onBack = onBack, immersive = true) {
        val d = desktop ?: return@Page
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item {
                GroupCard {
                    // The visible rows first: the last one is conditional, and each row needs its place in the card.
                    // Identifiers (fingerprint, device id) are what one compares or reports: copyable.
                    val rows = buildList {
                        add(Triple(stringResource(R.string.field_desktop), d.name, false))
                        add(Triple(stringResource(R.string.field_status), connectionText(conn), false))
                        add(Triple(stringResource(R.string.field_fingerprint), Fingerprints.grouped(d.fingerprint), true))
                        add(Triple(stringResource(R.string.field_last_host), (d.lastGoodHost ?: "—") + ":" + d.port, false))
                        add(Triple(stringResource(R.string.field_addresses), d.hosts.joinToString("\n"), false))
                        add(Triple(stringResource(R.string.field_device_id), d.deviceId, true))
                        add(Triple(stringResource(R.string.field_paired_at), Formatters.dateTime(d.pairedAtMs), false))
                        add(Triple(stringResource(R.string.field_step_up), if (d.stepUpEnabled) stringResource(R.string.stepup_enabled) else stringResource(R.string.stepup_disabled), false))
                        add(Triple(stringResource(R.string.field_key_storage), keyStorageLabel(container.keys.securityLevel(d.identityAlias)), false))
                        (conn as? ConnectionState.Online)?.status?.let { s ->
                            add(Triple(stringResource(R.string.field_server_version), listOfNotNull(s.serverVersion, s.platform?.takeIf { it.isNotBlank() }?.let(::platformLabel)).joinToString(" · "), false))
                        }
                    }
                    val ctx = LocalContext.current
                    val copied = stringResource(R.string.files_copied)
                    val copyLabel = stringResource(R.string.action_copy)
                    rows.forEachIndexed { i, (label, value, copyable) ->
                        if (copyable) {
                            io.github.suqi8.coui.kmp.basic.BasicComponent(
                                title = label,
                                summary = value,
                                onClick = {
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, value.replace(" ", "")))
                                    android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onClickLabel = copyLabel,
                                endActions = {
                                    io.github.suqi8.coui.kmp.basic.Icon(io.github.tianli0.switchtether.ui.icons.AppIcons.Copy, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
                                },
                                cardListPosition = cardListPositionOf(i, rows.size),
                            )
                        } else {
                            KeyValueRow(label, value, cardListPosition = cardListPositionOf(i, rows.size))
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.desktop_info_note), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp))
            }
        }
    }
}

@Composable
fun NotificationPrefsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val prefs by container.notifyPrefsState.collectAsStateWithLifecycle()
    fun update(t: (NotifyPrefs) -> NotifyPrefs) = container.appScope.launch { container.settingsStore.updateNotifyPrefs(t) }
    var quietPick by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    QuietTimeSheet(
        title = when (quietPick) {
            QUIET_START -> stringResource(R.string.notif_quiet_start)
            QUIET_END -> stringResource(R.string.notif_quiet_end)
            else -> null
        },
        minutes = when (quietPick) {
            QUIET_START -> prefs.quietStartMin
            QUIET_END -> prefs.quietEndMin
            else -> null
        },
        onDismiss = { quietPick = 0 },
    ) { m ->
        val which = quietPick
        quietPick = 0
        update { if (which == QUIET_START) it.copy(quietStartMin = m) else it.copy(quietEndMin = m) }
    }
    Page(title = stringResource(R.string.settings_notifications), onBack = onBack, immersive = true) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { SectionHeader(stringResource(R.string.live_settings_title)) }
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val live = container.liveUpdates
                var autoPin by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(live.autoPinPhoneSessions) }
                var promotable by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(live.canPromote()) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    promotable = live.canPromote()
                    onPauseOrDispose { }
                }
                GroupCard {
                    val fIntent = io.github.tianli0.switchtether.ui.common.fluidCloudSettingsIntent(ctx)
                    ArrowPreference(
                        title = stringResource(R.string.live_settings_status),
                        summary = stringResource(
                            when {
                                android.os.Build.VERSION.SDK_INT < 36 -> R.string.live_status_unsupported
                                promotable -> R.string.live_status_on
                                else -> R.string.live_status_off
                            },
                        ),
                        onClick = { runCatching { ctx.startActivity(io.github.tianli0.switchtether.ui.common.promotionSettingsIntent(ctx)) } },
                        cardListPosition = CardListPosition.Head,
                    )
                    fIntent?.let { intent ->
                        ArrowPreference(
                            title = stringResource(R.string.pin_open_fluid_cloud),
                            onClick = { runCatching { ctx.startActivity(intent) } },
                            cardListPosition = CardListPosition.Middle,
                        )
                    }
                    SwitchPreference(
                        checked = autoPin,
                        onCheckedChange = { v -> autoPin = v; live.autoPinPhoneSessions = v },
                        title = stringResource(R.string.live_auto_pin),
                        summary = stringResource(R.string.live_auto_pin_summary),
                        cardListPosition = CardListPosition.Tail,
                    )
                }
            }
            item {
                GroupCard {
                    NotifyType.entries.forEachIndexed { i, type ->
                        SwitchPreference(
                            checked = type in prefs.enabled,
                            onCheckedChange = { v -> update { p -> p.copy(enabled = if (v) p.enabled + type else p.enabled - type) } },
                            title = stringResource(Notifier.channelName(type)),
                            cardListPosition = cardListPositionOf(i, NotifyType.entries.size),
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.notif_quiet)) }
            item {
                GroupCard {
                    SwitchPreference(
                        checked = prefs.quietHoursEnabled,
                        onCheckedChange = { v -> update { it.copy(quietHoursEnabled = v) } },
                        // The section header already says "quiet hours": the switch says what it does,
                        // and the summary only promises silence while it is on.
                        title = stringResource(R.string.notif_quiet_switch),
                        summary = if (prefs.quietHoursEnabled) stringResource(R.string.notif_quiet_summary, hm(prefs.quietStartMin), hm(prefs.quietEndMin))
                        else stringResource(R.string.notif_quiet_off),
                        cardListPosition = CardListPosition.Head,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.notif_quiet_start),
                        summary = hm(prefs.quietStartMin),
                        enabled = prefs.quietHoursEnabled,
                        onClick = { quietPick = QUIET_START },
                        cardListPosition = CardListPosition.Middle,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.notif_quiet_end),
                        summary = hm(prefs.quietEndMin),
                        enabled = prefs.quietHoursEnabled,
                        onClick = { quietPick = QUIET_END },
                        cardListPosition = CardListPosition.Tail,
                    )
                }
            }
            // Mute by switching off, not by typing internal ids: known agents plus the projects of recent sessions.
            item { SectionHeader(stringResource(R.string.notif_muted_agents)) }
            item {
                val agents = (listOf("claude_code", "codex", "dsh") + prefs.mutedAgents).distinct()
                GroupCard {
                    agents.forEachIndexed { i, a ->
                        SwitchPreference(
                            checked = a in prefs.mutedAgents,
                            onCheckedChange = { v -> update { p -> p.copy(mutedAgents = if (v) p.mutedAgents + a else p.mutedAgents - a) } },
                            title = io.github.tianli0.switchtether.ui.common.Formatters.agentLabel(a),
                            cardListPosition = cardListPositionOf(i, agents.size),
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.notif_muted_projects)) }
            item {
                val container = io.github.tianli0.switchtether.ui.appContainer()
                var known by remember { mutableStateOf<List<String>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    known = runCatching { container.sessions.list(io.github.tianli0.switchtether.domain.SessionQuery(), limit = 100).items }
                        .getOrNull().orEmpty().mapNotNull { it.project?.takeIf { p -> p.isNotBlank() } }.distinct()
                }
                val projects = (prefs.mutedProjects.toList() + known).distinct()
                GroupCard {
                    if (projects.isEmpty()) {
                        io.github.suqi8.coui.kmp.basic.BasicComponent(title = stringResource(R.string.notif_muted_projects_empty), cardListPosition = CardListPosition.Full)
                    }
                    projects.forEachIndexed { i, proj ->
                        SwitchPreference(
                            checked = proj in prefs.mutedProjects,
                            onCheckedChange = { v -> update { p -> p.copy(mutedProjects = if (v) p.mutedProjects + proj else p.mutedProjects - proj) } },
                            title = proj,
                            cardListPosition = cardListPositionOf(i, projects.size),
                        )
                    }
                }
            }
            item {
                GroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.notif_system_settings),
                        cardListPosition = CardListPosition.Head,
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        },
                    )
                    var unrestricted by remember { mutableStateOf(false) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                        unrestricted = context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
                        onPauseOrDispose { }
                    }
                    ArrowPreference(
                        title = stringResource(R.string.notif_battery),
                        summary = stringResource(if (unrestricted) R.string.notif_battery_unrestricted else R.string.notif_battery_restricted),
                        cardListPosition = CardListPosition.Tail,
                        onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                    )
                }
            }
            item { Text(stringResource(R.string.notif_privacy), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter)) }
        }
    }
}

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

data class LicenseEntry(val name: String, val license: String, val note: String)

/** Mirrors android/THIRD_PARTY_LICENSES.md. */
val LICENSES = listOf(
    LicenseEntry("COUI17 (TIANLI0/COUI17, from Costben/coui17 @ 7135f75)", "Apache-2.0 (+ MPL-2.0 files in kit/**)", "Built from source (git submodule third_party/coui17). MPL-2.0 sources: https://github.com/TIANLI0/COUI17/tree/c78553dc7d17906f414728bb62894ba2ee43dbc3/coui-ui/src/commonMain/kotlin/io/github/suqi8/coui/kmp/kit and https://github.com/TIANLI0/COUI17/tree/c78553dc7d17906f414728bb62894ba2ee43dbc3/coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/kit"),
    LicenseEntry("COUI (suqi8/coui), Miuix (compose-miuix-ui/miuix)", "Apache-2.0", "Upstream projects of coui17 (NOTICE)"),
    LicenseEntry("COUI-Kit (ItosEO/COUI-Kit)", "MPL-2.0", "Ported files keep their MPL-2.0 headers"),
    LicenseEntry("Jetpack Compose / Compose Multiplatform", "Apache-2.0", "Google LLC, JetBrains s.r.o."),
    LicenseEntry("AndroidX (activity, core, fragment, lifecycle, datastore, biometric, camera, work, dynamicanimation, navigationevent)", "Apache-2.0", "The Android Open Source Project"),
    LicenseEntry("Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "JetBrains s.r.o."),
    LicenseEntry("OkHttp / Okio", "Apache-2.0", "Square, Inc."),
    LicenseEntry("ZXing core", "Apache-2.0", "ZXing authors"),
    LicenseEntry("Telephoto (zoomable, sub-sampling-image, flick)", "Apache-2.0", "Saket Narayan"),
    LicenseEntry("multiplatform-markdown-renderer, JetBrains Markdown", "Apache-2.0", "Mike Penz; JetBrains s.r.o."),
    LicenseEntry("Highlights (dev.snipme)", "Apache-2.0", "SnipMe"),
    LicenseEntry("Lottie for Android (airbnb/lottie-android)", "Apache-2.0", "Airbnb, Inc."),
    LicenseEntry("useAnimations (useAnimations/react-useanimations @ f7af6f5): clock, gitBranch, notification4", "MIT", "Copyright (c) useAnimations. Animated icons bundled as res/raw/lottie_*.json"),
    LicenseEntry("AndroidX Media3 ExoPlayer (video / audio preview)", "Apache-2.0", "The Android Open Source Project"),
    LicenseEntry("material-color-utilities (com.materialkolor)", "Apache-2.0", "Google LLC / Jordon de Hoog"),
    LicenseEntry("material3-window-size-class (JetBrains)", "Apache-2.0", "JetBrains s.r.o."),
    LicenseEntry("Phosphor Icons (com.adamglin:phosphor-icon 1.0.0)", "MIT", "Icons by Phosphor Icons (Helena Zhang, Tobias Fried); Compose port by Adam Glin"),
    LicenseEntry("LobeHub Icons (Claude and OpenAI marks, @lobehub/icons-static-svg 1.102.0)", "MIT", "Copyright (c) 2023 LobeHub. Claude is a trademark of Anthropic PBC, OpenAI a trademark of OpenAI; used only to identify the agent."),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Page(title = stringResource(R.string.settings_licenses), onBack = onBack, immersive = true) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { Text(stringResource(R.string.licenses_intro), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter, vertical = 12.dp)) }
            item {
                GroupCard {
                  androidx.compose.foundation.text.selection.SelectionContainer {
                   Column {
                    LICENSES.forEachIndexed { i, l ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            // The name gets the full width (a long licence label beside it squeezed
                            // "COUI17 (…)" into a four-line column); the licence is a quiet line under
                            // it, not link-blue — it is not a link.
                            Text(l.name, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
                            Text(l.license, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceSecondary)
                            Text(l.note, style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (i < LICENSES.lastIndex) io.github.suqi8.coui.kmp.basic.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                   }
                  }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private const val QUIET_START = 1
private const val QUIET_END = 2

/**
 * The quiet-hours time in a COUI half-screen panel with the COUI wheel picker (hours and minutes,
 * 12/24 h as the phone is set). The platform TimePickerDialog it replaces was the one teal,
 * square-cornered dialog in the app.
 */
@Composable
private fun QuietTimeSheet(title: String?, minutes: Int?, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    var value by androidx.compose.runtime.saveable.rememberSaveable(minutes) { mutableStateOf((minutes ?: 0)) }
    io.github.tianli0.coui17.patterns.PanelSheet(
        show = minutes != null,
        title = title,
        onDismissRequest = onDismiss,
        confirm = io.github.tianli0.coui17.patterns.SheetConfirm(stringResource(R.string.action_done)) { onPick(value) },
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            io.github.suqi8.coui.kmp.basic.TimePicker(
                value = io.github.suqi8.coui.kmp.basic.TimeValue(value / 60, value % 60),
                onValueChange = { value = it.hour * 60 + it.minute },
                is24Hour = android.text.format.DateFormat.is24HourFormat(context),
                amLabel = stringResource(R.string.time_am),
                pmLabel = stringResource(R.string.time_pm),
            )
        }
    }
}

/** Where the phone's pairing key lives, in words (the Keystore reports strongbox / tee / software). */
@Composable
private fun keyStorageLabel(level: String): String = when (level) {
    "strongbox" -> stringResource(R.string.key_storage_strongbox)
    "tee" -> stringResource(R.string.key_storage_tee)
    "software" -> stringResource(R.string.key_storage_software)
    "missing" -> stringResource(R.string.key_storage_missing)
    else -> stringResource(R.string.key_storage_unknown)
}

/** The computer's OS as people write it (the server reports `windows`, `macos`, `linux`). */
private fun platformLabel(p: String): String = when (p.lowercase()) {
    "windows" -> "Windows"
    "macos", "darwin" -> "macOS"
    "linux" -> "Linux"
    else -> p
}
