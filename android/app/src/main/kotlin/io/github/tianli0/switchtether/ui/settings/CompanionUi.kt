package io.github.tianli0.switchtether.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import io.github.tianli0.switchtether.ui.common.GroupCard
import io.github.tianli0.switchtether.ui.common.SheetTextGutter
import io.github.tianli0.switchtether.ui.components.ActionTone
import io.github.tianli0.switchtether.ui.components.SmallButton
import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.connection.CompanionLink
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.common.AppBottomSheet

/**
 * Settings row "Bind computer (Bluetooth)" and its sheet: associate the computer through the
 * system's CompanionDeviceManager dialog, unbind, and "only while nearby".
 */
@Composable
fun CompanionPreference(cardListPosition: CardListPosition) {
    val container = appContainer()
    val link = container.companion
    if (!link.isSupported) return
    val associations by link.associations.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.companion_title),
        summary = if (associations.isEmpty()) {
            stringResource(R.string.companion_summary_none)
        } else {
            stringResource(R.string.companion_summary_bound, associations.joinToString("、") { it.name })
        },
        cardListPosition = cardListPosition,
        onClick = { show = true },
    )
    CompanionSheet(show = show, onDismiss = { show = false })
}

@Composable
private fun CompanionSheet(show: Boolean, onDismiss: () -> Unit) {
    val container = appContainer()
    val link = container.companion
    val associations by link.associations.collectAsStateWithLifecycle()
    val nearbyOnly by link.nearbyOnly.collectAsStateWithLifecycle()
    var bonded by remember { mutableStateOf(emptyList<CompanionLink.BondedComputer>()) }
    var btGranted by remember { mutableStateOf(link.hasBluetoothPermission()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun reload() {
        link.refresh()
        btGranted = link.hasBluetoothPermission()
        bonded = link.bondedComputers()
    }
    LifecycleResumeEffect(show) {
        if (show) reload()
        onPauseOrDispose { }
    }
    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            message = null
            link.refresh()
            link.observeAll()
        }
        reload()
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }
    val failedText = stringResource(R.string.companion_failed)
    val callback = remember {
        object : CompanionLink.Callback {
            override fun onChooser(sender: IntentSender) {
                chooserLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }

            override fun onFailure(error: String?) {
                message = failedText.format(error ?: "—")
            }
        }
    }

    AppBottomSheet(show = show, title = stringResource(R.string.companion_title), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.companion_intro),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = SheetTextGutter).padding(bottom = 12.dp),
            )
            if (associations.isNotEmpty()) {
                GroupCard {
                    associations.forEachIndexed { i, a ->
                        BasicComponent(
                            title = a.name,
                            summary = a.address,
                            cardListPosition = cardListPositionOf(i, associations.size + 1),
                            endActions = { SmallButton(stringResource(R.string.companion_unbind), { link.disassociate(a); reload() }, tone = ActionTone.Danger) },
                        )
                    }
                    SwitchPreference(
                        checked = nearbyOnly,
                        onCheckedChange = { v ->
                            link.setNearbyOnly(v)
                            // Re-evaluates the monitor at once: starts it when allowed again.
                            container.onCompanionPresence(link.present.value)
                        },
                        title = stringResource(R.string.companion_nearby_only),
                        summary = stringResource(
                            if (link.canObservePresence) R.string.companion_nearby_only_summary else R.string.companion_no_presence,
                        ),
                        enabled = link.canObservePresence,
                        cardListPosition = CardListPosition.Tail,
                    )
                }
            }
            if (bonded.isNotEmpty()) {
                GroupCard {
                    bonded.forEachIndexed { i, pc ->
                        BasicComponent(
                            title = pc.name,
                            summary = pc.address,
                            cardListPosition = cardListPositionOf(i, bonded.size),
                            endActions = { SmallButton(stringResource(R.string.companion_bind), { link.associate(callback, pc.address) }) },
                        )
                    }
                }
            }
            // Settings rows like the others on this page, not full-width buttons.
            val needsBt = !btGranted && Build.VERSION.SDK_INT >= 31
            GroupCard {
                if (needsBt) {
                    ArrowPreference(
                        title = stringResource(R.string.companion_bt_permission),
                        cardListPosition = CardListPosition.Head,
                        onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.companion_scan),
                    cardListPosition = if (needsBt) CardListPosition.Tail else CardListPosition.Full,
                    onClick = { link.associate(callback) },
                )
            }
            message?.let {
                Text(it, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.error, modifier = Modifier.padding(horizontal = SheetTextGutter).padding(bottom = 8.dp))
            }
        }
    }
}
