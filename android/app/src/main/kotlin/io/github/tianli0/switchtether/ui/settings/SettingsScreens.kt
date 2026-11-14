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
import io.github.tianli0.switchtether.data.NotificationType
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
    val background by container.settingsStore.backgroundConnection.collectAsStateWithLifecycle(false)
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
                        onCheckedChange = { v -> scope.launch { container.settingsStore.setBackgroundConnection(v) } },
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
    val scope = rememberCoroutineScope()
    val disabled by container.settingsStore.disabledNotifications.collectAsStateWithLifecycle(emptySet())
    Page(title = stringResource(R.string.settings_notifications), onBack = onBack) {
        Column {
            Text(stringResource(R.string.notifications_phase_b), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
            PageCard {
                listOf(
                    NotificationType.APPROVAL_REQUESTED to R.string.notif_approval,
                    NotificationType.SESSION_WAITING to R.string.notif_waiting,
                    NotificationType.SESSION_FINISHED to R.string.notif_finished,
                    NotificationType.SESSION_ERROR to R.string.notif_error,
                    NotificationType.CONNECTION_LOST to R.string.notif_connection,
                ).forEach { (type, label) ->
                    SwitchPreference(
                        checked = type !in disabled,
                        onCheckedChange = { v -> scope.launch { container.settingsStore.setNotificationEnabled(type, v) } },
                        title = stringResource(label),
                    )
                }
            }
        }
    }
}

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
