package io.github.tianli0.switchtether.ui.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.BuildConfig
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.notify.Notifier
import io.github.tianli0.switchtether.remote.notify.NotifyPrefs
import io.github.tianli0.switchtether.remote.notify.NotifyType
import io.github.tianli0.switchtether.ui.common.TextInput
import io.github.tianli0.switchtether.ui.common.pageListPadding
import io.github.tianli0.switchtether.ui.common.pageScroll
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.security.StepUpSignException
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.ConfirmDialog
import io.github.tianli0.switchtether.ui.common.Formatters
import io.github.tianli0.switchtether.ui.common.Page
import io.github.tianli0.switchtether.ui.common.connectionText
import io.github.tianli0.switchtether.ui.components.KeyValueRow
import io.github.tianli0.switchtether.ui.components.PageCard
import io.github.tianli0.switchtether.ui.components.SectionHeader
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpen: (Route) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val desktop by container.pairingStore.desktop.collectAsStateWithLifecycle(null)
    val theme by container.settingsStore.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val background by container.settingsStore.backgroundConnection.collectAsStateWithLifecycle(true)
    val conn by container.connection.state.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var stepUpResult by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? FragmentActivity
    val stepUpTitle = stringResource(R.string.stepup_prompt_title)
    val stepUpSubtitle = stringResource(R.string.stepup_prompt_subtitle)
    val okFmt = stringResource(R.string.stepup_ok)
    val failFmt = stringResource(R.string.stepup_failed)

    Page(title = stringResource(R.string.tab_settings)) {
        LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { SectionHeader(stringResource(R.string.settings_desktop)) }
            item {
                PageCard {
                    ArrowPreference(
                        title = desktop?.name ?: "—",
                        summary = connectionText(conn) + "\n" + (desktop?.fingerprint?.let { Fingerprints.short(it) } ?: ""),
                        onClick = { onOpen(Route.DesktopInfo) },
                    )
                    ArrowPreference(title = stringResource(R.string.settings_unpair), summary = stringResource(R.string.settings_unpair_summary), onClick = { confirmUnpair = true })
                }
            }
            item { SectionHeader(stringResource(R.string.settings_security)) }
            item {
                PageCard {
                    val stepUp = desktop?.stepUpEnabled == true
                    ArrowPreference(
                        title = stringResource(R.string.settings_stepup_test),
                        summary = stepUpResult ?: if (stepUp) stringResource(R.string.settings_stepup_summary) else stringResource(R.string.settings_stepup_disabled),
                        enabled = stepUp && conn is ConnectionState.Online && activity != null,
                        onClick = {
                            val act = activity ?: return@ArrowPreference
                            scope.launch {
                                stepUpResult = try {
                                    val grant = container.stepUp(act, StepUpScopes.CONFIG_WRITE, stepUpTitle, stepUpSubtitle)
                                    okFmt.format(grant.scope, grant.expiresAt)
                                } catch (e: StepUpSignException) {
                                    failFmt.format(e.reason.name + (e.message?.let { ": $it" } ?: ""))
                                } catch (e: Exception) {
                                    failFmt.format(ConnectionManager.describe(e))
                                }
                            }
                        },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                PageCard {
                    listOf(
                        ThemeMode.SYSTEM to R.string.theme_system,
                        ThemeMode.LIGHT to R.string.theme_light,
                        ThemeMode.DARK to R.string.theme_dark,
                    ).forEach { (mode, label) ->
                        RadioButtonPreference(
                            title = stringResource(label),
                            selected = theme == mode,
                            onClick = { scope.launch { container.settingsStore.setThemeMode(mode) } },
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.settings_connection)) }
            item {
                PageCard {
                    SwitchPreference(
                        checked = background,
                        onCheckedChange = { v -> container.setBackgroundConnection(v) },
                        title = stringResource(R.string.settings_background),
                        summary = stringResource(R.string.settings_background_summary),
                    )
                    ArrowPreference(title = stringResource(R.string.settings_notifications), summary = stringResource(R.string.settings_notifications_summary), onClick = { onOpen(Route.NotificationPrefs) })
                }
            }
            item { SectionHeader(stringResource(R.string.settings_storage)) }
            item {
                PageCard {
                    ArrowPreference(title = stringResource(R.string.settings_clear_cache), summary = stringResource(R.string.settings_clear_cache_summary), onClick = { confirmClear = true })
                }
            }
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                PageCard {
                    KeyValueRow(stringResource(R.string.settings_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    KeyValueRow(stringResource(R.string.settings_protocol), "v${io.github.tianli0.switchtether.remote.protocol.CLIENT_PROTOCOL_VERSION}")
                    ArrowPreference(title = stringResource(R.string.settings_licenses), onClick = { onOpen(Route.Licenses) })
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    ConfirmDialog(
        show = confirmUnpair,
        title = stringResource(R.string.settings_unpair),
        message = stringResource(R.string.settings_unpair_confirm, desktop?.name ?: ""),
        confirmText = stringResource(R.string.settings_unpair),
        onConfirm = {
            confirmUnpair = false
            scope.launch { container.unpair() }
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
        },
        onDismiss = { confirmClear = false },
    )
}

@Composable
fun DesktopInfoScreen(onBack: () -> Unit) {
    val container = appContainer()
    val desktop by container.pairingStore.desktop.collectAsStateWithLifecycle(null)
    val conn by container.connection.state.collectAsStateWithLifecycle()
    Page(title = stringResource(R.string.settings_desktop), onBack = onBack) {
        val d = desktop ?: return@Page
        LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item {
                PageCard {
                    KeyValueRow(stringResource(R.string.field_desktop), d.name)
                    KeyValueRow(stringResource(R.string.field_status), connectionText(conn))
                    KeyValueRow(stringResource(R.string.field_fingerprint), Fingerprints.grouped(d.fingerprint), mono = true)
                    KeyValueRow(stringResource(R.string.field_last_host), (d.lastGoodHost ?: "—") + ":" + d.port, mono = true)
                    KeyValueRow(stringResource(R.string.field_addresses), d.hosts.joinToString("\n"), mono = true)
                    KeyValueRow(stringResource(R.string.field_device_id), d.deviceId, mono = true)
                    KeyValueRow(stringResource(R.string.field_paired_at), Formatters.dateTime(d.pairedAtMs))
                    KeyValueRow(stringResource(R.string.field_step_up), if (d.stepUpEnabled) stringResource(R.string.stepup_enabled) else stringResource(R.string.stepup_disabled))
                    KeyValueRow(stringResource(R.string.field_key_storage), container.keys.securityLevel(d.identityAlias))
                    (conn as? ConnectionState.Online)?.status?.let { s ->
                        KeyValueRow(stringResource(R.string.field_server_version), "${s.serverVersion} · ${s.platform ?: ""}")
                    }
                }
            }
            item {
                Text(stringResource(R.string.desktop_info_note), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
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
    Page(title = stringResource(R.string.settings_notifications), onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item {
                PageCard {
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
                PageCard {
                    SwitchPreference(
                        checked = prefs.quietHoursEnabled,
                        onCheckedChange = { v -> update { it.copy(quietHoursEnabled = v) } },
                        title = stringResource(R.string.notif_quiet),
                        summary = stringResource(R.string.notif_quiet_summary, hm(prefs.quietStartMin), hm(prefs.quietEndMin)),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        TextInput((prefs.quietStartMin / 60).toString(), { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { h -> update { it.copy(quietStartMin = h * 60) } } }, stringResource(R.string.notif_quiet_start), modifier = Modifier.weight(1f))
                        Spacer(Modifier.size(12.dp))
                        TextInput((prefs.quietEndMin / 60).toString(), { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { h -> update { it.copy(quietEndMin = h * 60) } } }, stringResource(R.string.notif_quiet_end), modifier = Modifier.weight(1f))
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.notif_muted_agents)) }
            item {
                PageCard {
                    Column(Modifier.padding(16.dp)) {
                        TextInput(prefs.mutedAgents.joinToString(", "), { v -> update { it.copy(mutedAgents = splitList(v)) } }, stringResource(R.string.notif_muted_hint))
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.notif_muted_projects)) }
            item {
                PageCard {
                    Column(Modifier.padding(16.dp)) {
                        TextInput(prefs.mutedProjects.joinToString(", "), { v -> update { it.copy(mutedProjects = splitList(v)) } }, stringResource(R.string.notif_muted_hint))
                    }
                }
            }
            item {
                PageCard {
                    ArrowPreference(
                        title = stringResource(R.string.notif_system_settings),
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.notif_battery),
                        summary = stringResource(R.string.notif_battery_summary),
                        onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                    )
                }
            }
            item { Text(stringResource(R.string.notif_privacy), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp)) }
        }
    }
}

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private fun splitList(v: String): Set<String> = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

data class LicenseEntry(val name: String, val license: String, val note: String)

/** Mirrors android/THIRD_PARTY_LICENSES.md. */
val LICENSES = listOf(
    LicenseEntry("coui17 (Costben/coui17 @ a51f957)", "Apache-2.0 (+ MPL-2.0 files in kit/**)", "Vendored source, see third_party/coui17. MPL-2.0 sources: https://github.com/Costben/coui17/tree/a51f95746292b3b06993b15fe46be1e2b9178421/coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/kit"),
    LicenseEntry("COUI (suqi8/coui), Miuix (compose-miuix-ui/miuix)", "Apache-2.0", "Upstream projects of coui17 (NOTICE)"),
    LicenseEntry("COUI-Kit (ItosEO/COUI-Kit)", "MPL-2.0", "Ported files keep their MPL-2.0 headers"),
    LicenseEntry("Jetpack Compose / Compose Multiplatform", "Apache-2.0", "Google LLC, JetBrains s.r.o."),
    LicenseEntry("AndroidX (activity, core, fragment, lifecycle, datastore, biometric, camera, dynamicanimation, navigationevent)", "Apache-2.0", "The Android Open Source Project"),
    LicenseEntry("Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "JetBrains s.r.o."),
    LicenseEntry("OkHttp / Okio", "Apache-2.0", "Square, Inc."),
    LicenseEntry("ZXing core", "Apache-2.0", "ZXing authors"),
    LicenseEntry("material-color-utilities (com.materialkolor)", "Apache-2.0", "Google LLC / Jordon de Hoog"),
    LicenseEntry("material3-window-size-class (JetBrains)", "Apache-2.0", "JetBrains s.r.o."),
    LicenseEntry("Phosphor Icons (com.adamglin:phosphor-icon 1.0.0)", "MIT", "Icons by Phosphor Icons (Helena Zhang, Tobias Fried); Compose port by Adam Glin"),
    LicenseEntry("LobeHub Icons (Claude and OpenAI marks, @lobehub/icons-static-svg 1.102.0)", "MIT", "Copyright (c) 2023 LobeHub. Claude is a trademark of Anthropic PBC, OpenAI a trademark of OpenAI; used only to identify the agent."),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Page(title = stringResource(R.string.settings_licenses), onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { Text(stringResource(R.string.licenses_intro), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp)) }
            items(LICENSES) { l ->
                PageCard {
                    Column(Modifier.padding(14.dp)) {
                        Text(l.name, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
                        Text(l.license, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
                        Text(l.note, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
