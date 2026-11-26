package io.github.tianli0.switchtether.ui.onboarding

import io.github.tianli0.coui17.patterns.ActionTone
import io.github.tianli0.coui17.patterns.LinkButton
import io.github.tianli0.coui17.patterns.StepButtons
import io.github.tianli0.coui17.patterns.SmallButton
import android.Manifest
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.rememberPageScrollState
import io.github.tianli0.switchtether.BuildConfig
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.domain.PairingException
import io.github.tianli0.switchtether.domain.PairingResult
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.tls.Fingerprints
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.coui17.patterns.TextInput
import io.github.tianli0.coui17.patterns.NoticeBanner
import io.github.tianli0.switchtether.ui.containerViewModel
import io.github.tianli0.switchtether.ui.theme.DevType
import io.github.tianli0.switchtether.ui.theme.LocalDevColors
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(revokedReason: Pair<String, String?>?) {
    val container = appContainer()
    val vm = containerViewModel { c ->
        PairingViewModel(
            repository = c.pairing,
            deviceSecure = { c.keys.isDeviceSecure() },
            deviceName = c.deviceName,
            osVersion = Build.VERSION.RELEASE ?: "",
            appVersion = BuildConfig.VERSION_NAME,
            onSuccess = { c.pendingVerification.value = it },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val linkText by vm.linkText.collectAsStateWithLifecycle()
    val pending by container.pendingVerification.collectAsStateWithLifecycle()
    val incoming by container.incomingPairingLink.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(incoming) {
        incoming?.let {
            container.incomingPairingLink.value = null
            vm.submitLink(it)
        }
    }
    val scope = rememberCoroutineScope()
    val shown = pending?.let { PairingUiState.Done(it) } ?: state
    val back = io.github.tianli0.switchtether.ui.common.rememberPredictiveBack(enabled = shown !is PairingUiState.Intro && shown !is PairingUiState.Done) { vm.backToIntro() }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().then(back)) {
    OnboardingContent(
        state = shown,
        linkText = linkText,
        deviceSecure = vm.isDeviceSecure,
        revokedReason = revokedReason,
        onLinkTextChange = vm::onLinkTextChange,
        onSubmitLink = { vm.submitLink() },
        onScan = vm::startScan,
        onScanned = vm::onScanned,
        onPair = { withLocalNetwork(vm.pendingHosts()) { vm.pair() } },
        onPickNearby = vm::pickNearby,
        onEnterAddress = vm::enterAddress,
        onSubmitCode = { address, code -> withLocalNetwork(vm.pendingHosts(address)) { vm.submitCode(address, code) } },
        onBack = vm::backToIntro,
        onDone = { container.onPaired() },
        onRejectCodes = { scope.launch { container.unpair(); container.pendingVerification.value = null; vm.backToIntro() } },
    )
    }
}

/**
 * Android 17: pairing over a LAN address needs ACCESS_LOCAL_NETWORK. Explain + ask first; pairing
 * continues either way (a VPN/Tailscale address may still work).
 */
private fun withLocalNetwork(hosts: List<String>, action: () -> Unit) {
    if (hosts.any(io.github.tianli0.switchtether.remote.pairing.LocalNetwork::isLocal)) {
        io.github.tianli0.switchtether.ui.common.localNetworkAsk.value = io.github.tianli0.switchtether.ui.common.LocalNetworkAsk { action() }
    } else {
        action()
    }
}

@Composable
fun OnboardingContent(
    state: PairingUiState,
    linkText: String,
    deviceSecure: Boolean,
    revokedReason: Pair<String, String?>?,
    onLinkTextChange: (String) -> Unit,
    onSubmitLink: () -> Unit,
    onScan: () -> Unit,
    onScanned: (String) -> Unit,
    onPair: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onRejectCodes: () -> Unit,
    onPickNearby: (io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop) -> Unit = {},
    onEnterAddress: () -> Unit = {},
    onSubmitCode: (address: String, code: String) -> Unit = { _, _ -> },
    /** Overrides LAN discovery (tests / previews); null = live NSD results. */
    nearbyOverride: List<io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop>? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberPageScrollState())
            .padding(horizontal = io.github.tianli0.coui17.patterns.PageDefaults.CardGutter, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnPageColumn(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            when (state) {
                PairingUiState.Intro -> IntroContent(linkText, deviceSecure, revokedReason, onLinkTextChange, onSubmitLink, onScan, onPickNearby, onEnterAddress, nearbyOverride)
                PairingUiState.Scanning -> ScanContent(onScanned, onBack)
                is PairingUiState.Confirm -> ConfirmContent(state.link, deviceSecure, onPair, onBack)
                is PairingUiState.EnterCode -> CodeContent(state, deviceSecure, onSubmitCode, onBack)
                is PairingUiState.Pairing -> ProgressContent(state.target, onBack)
                is PairingUiState.LinkInvalid -> MessageContent(
                    title = stringResource(R.string.pair_invalid_title),
                    message = linkReasonText(state.reason, state.field),
                    primary = stringResource(R.string.action_back) to onBack,
                )
                is PairingUiState.Failed -> MessageContent(
                    title = stringResource(R.string.pair_failed_title),
                    message = pairingReasonText(state.reason, byCode = state.target is io.github.tianli0.switchtether.remote.pairing.PairingTarget.Code) +
                        (state.detail?.let { "\n\n$it" } ?: ""),
                    primary = stringResource(
                        if (state.target is io.github.tianli0.switchtether.remote.pairing.PairingTarget.Code) R.string.pair_code_reenter else R.string.action_retry,
                    ) to onPair,
                    secondary = stringResource(R.string.action_back) to onBack,
                )
                is PairingUiState.Done -> VerificationContent(state.result, onDone, onRejectCodes)
            }
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, style = COUITheme.textStyles.title2, color = COUITheme.colorScheme.onSurface, modifier = Modifier.padding(top = 24.dp).semantics { heading() })
    if (sub != null) {
        Spacer(Modifier.height(6.dp))
        Text(sub, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun IntroContent(
    linkText: String,
    deviceSecure: Boolean,
    revokedReason: Pair<String, String?>?,
    onLinkTextChange: (String) -> Unit,
    onSubmitLink: () -> Unit,
    onScan: () -> Unit,
    onPickNearby: (io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop) -> Unit,
    onEnterAddress: () -> Unit,
    nearbyOverride: List<io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop>?,
) {
    Title(stringResource(R.string.app_name), stringResource(R.string.onboarding_subtitle))
    if (revokedReason != null) {
        NoticeBanner(stringResource(R.string.onboarding_revoked, revokedReason.second ?: "—", revokedReason.first), LocalDevColors.current.danger, Modifier.clip(SquircleShape(10.dp)))
        Spacer(Modifier.height(12.dp))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onboarding_security_title), style = COUITheme.textStyles.body1, fontWeight = FontWeight.SemiBold, color = COUITheme.colorScheme.onSurface)
            listOf(
                R.string.onboarding_security_tls,
                R.string.onboarding_security_keys,
                R.string.onboarding_security_stepup,
                R.string.onboarding_security_local,
            ).forEach { Bullet(stringResource(it)) }
        }
    }
    if (!deviceSecure) {
        Spacer(Modifier.height(12.dp))
        NoticeBanner(stringResource(R.string.onboarding_no_lock_screen), LocalDevColors.current.warn, Modifier.clip(SquircleShape(10.dp)))
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth().testTag("scan"),
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) { Text(stringResource(R.string.onboarding_scan), color = COUITheme.colorScheme.onPrimary) }
    NearbyDesktops(onPickNearby, nearbyOverride)
    Spacer(Modifier.height(4.dp))
    // One filled button per step; the other ways in are quiet links.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LinkButton(stringResource(R.string.pair_enter_address), onEnterAddress, modifier = Modifier.testTag("enter-address"))
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.onboarding_paste_label), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.height(6.dp))
    TextInput(
        value = linkText,
        onValueChange = onLinkTextChange,
        modifier = Modifier.fillMaxWidth().testTag("pairing-link"),
        label = "switchtether://pair?…",
        singleLine = false,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (linkText.isNotBlank()) onSubmitLink() }),
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (linkText.isBlank()) {
            // The link usually arrives through the clipboard (copied on the computer, synced or sent to the phone).
            val context = androidx.compose.ui.platform.LocalContext.current
            SmallButton(
                text = stringResource(R.string.onboarding_paste),
                onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                    if (!clip.isNullOrEmpty()) onLinkTextChange(clip)
                },
                modifier = Modifier.testTag("paste-link"),
            )
        } else {
            SmallButton(stringResource(R.string.onboarding_use_link), onSubmitLink, tone = ActionTone.Primary, modifier = Modifier.testTag("use-link"))
        }
    }
}

/**
 * The onboarding column. It is not inside a `Page`, so it says itself that its content sits on the
 * page colour: text fields then fill themselves with the card colour instead of vanishing.
 */
@Composable
private fun OnPageColumn(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(io.github.tianli0.coui17.patterns.LocalOnPageColor provides true) {
        Column(modifier) { content() }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", color = COUITheme.colorScheme.primary, style = COUITheme.textStyles.body2, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
    }
}

@Composable
private fun ScanContent(onScanned: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    Title(stringResource(R.string.scan_title), stringResource(R.string.scan_hint))
    val cameraDesc = stringResource(R.string.camera_preview_desc)
    if (granted) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(SquircleShape(16.dp)).semantics { contentDescription = cameraDesc }) {
            QrScanner(onResult = onScanned, modifier = Modifier.fillMaxSize())
        }
    } else {
        Text(
            if (denied) stringResource(R.string.scan_permission_denied) else stringResource(R.string.scan_permission_rationale),
            style = COUITheme.textStyles.body2,
            color = COUITheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))
        StepButtons(
            primary = stringResource(R.string.scan_grant),
            onPrimary = { launcher.launch(Manifest.permission.CAMERA) },
            secondary = stringResource(R.string.action_cancel),
            onSecondary = onBack,
        )
        return
    }
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LinkButton(stringResource(R.string.action_cancel), onBack)
    }
}

@Composable
private fun ConfirmContent(link: PairingLink, deviceSecure: Boolean, onPair: () -> Unit, onBack: () -> Unit) {
    Title(stringResource(R.string.confirm_title), stringResource(R.string.confirm_subtitle))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.field_desktop), link.desktopName)
            Field(stringResource(R.string.field_addresses), link.hosts.joinToString("\n") { h -> (if (h.contains(':')) "[$h]" else h) + ":" + link.port })
            Field(stringResource(R.string.field_fingerprint), Fingerprints.grouped(link.fingerprint), mono = true)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.confirm_fingerprint_hint), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    if (!deviceSecure) {
        Spacer(Modifier.height(10.dp))
        NoticeBanner(stringResource(R.string.onboarding_no_lock_screen), LocalDevColors.current.warn, Modifier.clip(SquircleShape(10.dp)))
    }
    Spacer(Modifier.height(20.dp))
    StepButtons(
        primary = stringResource(R.string.confirm_pair),
        onPrimary = onPair,
        primaryTag = "pair",
        secondary = stringResource(R.string.action_cancel),
        onSecondary = onBack,
    )
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = if (mono) DevType.code else COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
    }
}

@Composable
private fun CodeContent(
    state: PairingUiState.EnterCode,
    deviceSecure: Boolean,
    onSubmit: (address: String, code: String) -> Unit,
    onBack: () -> Unit,
) {
    // After a failed attempt the typed address comes back pre-filled, ready to correct.
    var address by rememberSaveable(state.manual, state.host) {
        mutableStateOf(
            state.host?.takeIf { state.manual }?.let { h ->
                val host = if (h.contains(':')) "[$h]" else h
                if (state.port == io.github.tianli0.switchtether.remote.pairing.PairingLink.DEFAULT_PORT) host else "$host:${state.port}"
            }.orEmpty(),
        )
    }
    var code by rememberSaveable(state.host, state.manual) { mutableStateOf("") }
    Title(stringResource(R.string.pair_code_title), stringResource(R.string.pair_code_subtitle))
    if (state.manual) {
        TextInput(
            value = address,
            onValueChange = { address = it },
            label = stringResource(R.string.pair_address_label),
            modifier = Modifier.fillMaxWidth().testTag("pair-address"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        )
        io.github.tianli0.switchtether.ui.common.NoticeVisibility(state.error == CodeError.INVALID_ADDRESS) { ErrorLine(stringResource(R.string.pair_address_invalid)) }
        Spacer(Modifier.height(12.dp))
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.field_desktop), state.desktopName ?: state.host.orEmpty())
                Field(stringResource(R.string.field_addresses), "${state.host}:${state.port}")
                state.fingerprint?.let { Field(stringResource(R.string.field_fingerprint), Fingerprints.grouped(it), mono = true) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    TextInput(
        value = code,
        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
        label = stringResource(R.string.pair_code_label),
        modifier = Modifier.fillMaxWidth().testTag("pair-code-input"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (code.length == 6) onSubmit(address, code) }),
    )
    io.github.tianli0.switchtether.ui.common.NoticeVisibility(state.error == CodeError.INVALID_CODE) { ErrorLine(stringResource(R.string.pair_code_invalid)) }
    Spacer(Modifier.height(12.dp))
    // Nothing vouches for this computer's certificate in advance on this path (the LAN
    // advertisement is unauthenticated too): the verification code is the real check.
    // A full sentence the user must read: wrapped text, not a one-line (marquee) tip.
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(SquircleShape(4.dp)).background(LocalDevColors.current.warn))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.pair_code_verify_warning), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
        }
    }
    if (!deviceSecure) {
        Spacer(Modifier.height(10.dp))
        NoticeBanner(stringResource(R.string.onboarding_no_lock_screen), LocalDevColors.current.warn, Modifier.clip(SquircleShape(10.dp)))
    }
    Spacer(Modifier.height(20.dp))
    StepButtons(
        primary = stringResource(R.string.confirm_pair),
        onPrimary = { onSubmit(address, code) },
        primaryEnabled = code.length == 6 && (!state.manual || address.isNotBlank()),
        primaryTag = "pair-code-submit",
        secondary = stringResource(R.string.action_cancel),
        onSecondary = onBack,
    )
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = COUITheme.textStyles.footnote1, color = LocalDevColors.current.danger, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ProgressContent(target: io.github.tianli0.switchtether.remote.pairing.PairingTarget, onCancel: () -> Unit) {
    Title(stringResource(R.string.pairing_title), stringResource(R.string.pairing_progress, target.hosts.joinToString(", ")))
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator(size = 36.dp)
    }
    // An unreachable address can take a while to fail: a visible way out (cancels the attempt).
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LinkButton(stringResource(R.string.action_cancel), onCancel)
    }
}

@Composable
private fun MessageContent(title: String, message: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Title(title)
    Text(message, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurface)
    Spacer(Modifier.height(20.dp))
    StepButtons(primary = primary.first, onPrimary = primary.second, secondary = secondary?.first, onSecondary = secondary?.second ?: {})
}

@Composable
private fun VerificationContent(result: PairingResult, onDone: () -> Unit, onReject: () -> Unit) {
    val dev = LocalDevColors.current
    Title(stringResource(R.string.verify_title, result.desktop.name), stringResource(R.string.verify_subtitle))
    val code = result.serverCode ?: result.localCode
    Text(
        code.chunked(3).joinToString(" "),
        fontFamily = FontFamily.Monospace,
        fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = COUITheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("verification-code").semantics {
            contentDescription = code.toCharArray().joinToString(" ")
        },
    )
    if (!result.codesMatch) {
        NoticeBanner(stringResource(R.string.verify_mismatch), dev.danger, Modifier.clip(SquircleShape(10.dp)))
        Spacer(Modifier.height(12.dp))
    }
    if (!result.stepUpEnabled) {
        NoticeBanner(stringResource(R.string.verify_no_stepup), dev.warn, Modifier.clip(SquircleShape(10.dp)))
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.height(8.dp))
    StepButtons(
        primary = stringResource(R.string.verify_match),
        onPrimary = onDone,
        primaryEnabled = result.codesMatch,
        primaryTag = "verify-done",
        secondary = stringResource(R.string.verify_no_match),
        onSecondary = onReject,
        secondaryAsLink = true,
    )
}

@Composable
fun linkReasonText(reason: PairingLink.Reason, field: String?): String = when (reason) {
    PairingLink.Reason.NOT_A_PAIRING_LINK -> stringResource(R.string.link_not_pairing)
    PairingLink.Reason.UNSUPPORTED_VERSION -> stringResource(R.string.link_unsupported_version)
    PairingLink.Reason.INVALID_ENCODING -> stringResource(R.string.link_invalid, field ?: "?")
    PairingLink.Reason.MISSING_FIELD, PairingLink.Reason.DUPLICATE_FIELD, PairingLink.Reason.INVALID_FIELD -> stringResource(R.string.link_invalid, field ?: "?")
}

@Composable
fun pairingReasonText(reason: PairingException.Reason, byCode: Boolean = false): String = stringResource(
    when (reason) {
        PairingException.Reason.UNREACHABLE -> R.string.pair_err_unreachable
        PairingException.Reason.CERTIFICATE_MISMATCH -> R.string.pair_err_cert_mismatch
        PairingException.Reason.EXPIRED -> if (byCode) R.string.pair_err_code_expired else R.string.pair_err_expired
        PairingException.Reason.WRONG_SECRET -> if (byCode) R.string.pair_err_wrong_code else R.string.pair_err_wrong_secret
        PairingException.Reason.RATE_LIMITED -> R.string.pair_err_rate_limited
        PairingException.Reason.REVOKED_CERT -> R.string.pair_err_revoked
        PairingException.Reason.INCOMPATIBLE_DESKTOP_TOO_OLD -> R.string.pair_err_desktop_old
        PairingException.Reason.INCOMPATIBLE_APP_TOO_OLD -> R.string.pair_err_app_old
        PairingException.Reason.SERVER_FINGERPRINT_MISMATCH -> R.string.pair_err_cert_mismatch
        PairingException.Reason.FAILED -> R.string.pair_err_failed
    },
)

/**
 * "附近的电脑": SwitchTether desktops advertised on this network (NSD, only while this screen is
 * visible). Tapping one asks for the 6-digit pairing code shown on that computer.
 */
@Composable
private fun NearbyDesktops(
    onPick: (io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop) -> Unit,
    override: List<io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop>?,
) {
    val nearby = override ?: run {
        val discovery = appContainer().discovery
        val live by androidx.compose.runtime.produceState(initialValue = emptyList<io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop>(), discovery) {
            discovery.browse().collect { value = it.distinctBy { d -> d.fingerprint } }
        }
        live
    }
    if (nearby.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.nearby_title), style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.height(6.dp))
    Card(Modifier.fillMaxWidth().testTag("nearby")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            nearby.forEach { d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(SquircleShape(8.dp))
                        .clickable { onPick(d) }
                        .testTag("nearby-${d.fingerprint.take(8)}"),
                ) {
                    Text(d.name, style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface)
                    Text("${d.host}:${d.port} · ${Fingerprints.short(d.fingerprint)}…", style = DevType.codeSmall, color = COUITheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Text(stringResource(R.string.nearby_hint), style = COUITheme.textStyles.footnote2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
