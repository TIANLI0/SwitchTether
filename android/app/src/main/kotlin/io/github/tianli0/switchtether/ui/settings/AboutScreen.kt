package io.github.tianli0.switchtether.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.coui17.patterns.GroupCard
import io.github.tianli0.coui17.patterns.KeyValueRow
import io.github.tianli0.coui17.patterns.Page
import io.github.tianli0.coui17.patterns.pageListPadding
import io.github.tianli0.coui17.patterns.pageScroll
import io.github.tianli0.coui17.patterns.rememberPageListState
import io.github.tianli0.switchtether.BuildConfig
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.appContainer
import io.github.tianli0.switchtether.ui.nav.Route
import io.github.tianli0.switchtether.update.UpdateState
import kotlinx.coroutines.launch

/**
 * ColorOS "About": the app's icon, name and version at the top, then one group with the update
 * check, the pre-release switch, the protocol version and the open-source licences. It fits on
 * one screen; Settings links to it with a single row.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val updState by container.updates.state.collectAsStateWithLifecycle()
    var beta by rememberSaveable { mutableStateOf(container.updates.includePrerelease) }
    Page(title = stringResource(R.string.settings_about), onBack = onBack, immersive = true) {
        LazyColumn(state = rememberPageListState(), modifier = Modifier.fillMaxSize().pageScroll(), contentPadding = pageListPadding()) {
            item { AppHeader() }
            item {
                GroupCard {
                    ArrowPreference(
                        title = stringResource(R.string.update_check),
                        summary = updateSummary(updState, BuildConfig.VERSION_NAME),
                        cardListPosition = CardListPosition.Head,
                        onClick = {
                            scope.launch {
                                if (container.updates.check() is UpdateState.Available) container.showUpdateRequest.value = true
                            }
                        },
                        modifier = Modifier.testTag("update-check"),
                    )
                    SwitchPreference(
                        checked = beta,
                        onCheckedChange = { v -> beta = v; container.updates.includePrerelease = v },
                        title = stringResource(R.string.update_beta),
                        summary = stringResource(R.string.update_beta_summary),
                        cardListPosition = CardListPosition.Middle,
                    )
                    KeyValueRow(stringResource(R.string.settings_protocol), "v${io.github.tianli0.switchtether.remote.protocol.CLIENT_PROTOCOL_VERSION}", cardListPosition = CardListPosition.Middle)
                    ArrowPreference(
                        title = stringResource(R.string.settings_licenses),
                        onClick = { onOpen(Route.Licenses) },
                        cardListPosition = CardListPosition.Tail,
                    )
                }
            }
            // The developer and the project, as on the author's other apps' About pages (THRM):
            // who makes it and where to reach them, then the code, feedback and related projects.
            item { io.github.suqi8.coui.kmp.basic.SmallTitle(stringResource(R.string.about_developer)) }
            item {
                GroupCard {
                    LinkRow(stringResource(R.string.about_author_name), stringResource(R.string.about_author_summary), "https://github.com/TIANLI0", CardListPosition.Head)
                    LinkRow(stringResource(R.string.about_homepage), "tianli0.top", "https://tianli0.top", CardListPosition.Middle)
                    LinkRow(stringResource(R.string.about_blog), "blog.tianli0.top", "https://blog.tianli0.top", CardListPosition.Middle)
                    LinkRow(stringResource(R.string.about_email), AUTHOR_EMAIL, "mailto:$AUTHOR_EMAIL", CardListPosition.Tail)
                }
            }
            item { io.github.suqi8.coui.kmp.basic.SmallTitle(stringResource(R.string.about_project)) }
            item {
                GroupCard {
                    LinkRow(stringResource(R.string.about_source), "github.com/TIANLI0/SwitchTether", "$REPO_URL", CardListPosition.Head)
                    LinkRow(stringResource(R.string.about_feedback), stringResource(R.string.about_feedback_summary), "$REPO_URL/issues", CardListPosition.Middle)
                    LinkRow(stringResource(R.string.about_releases), stringResource(R.string.about_releases_summary), "$REPO_URL/releases", CardListPosition.Middle)
                    LinkRow("COUI17", stringResource(R.string.about_coui17_summary), "https://github.com/TIANLI0/COUI17", CardListPosition.Middle)
                    LinkRow("CC Switch", stringResource(R.string.about_upstream_summary), "https://github.com/farion1231/cc-switch", CardListPosition.Tail)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private const val REPO_URL = "https://github.com/TIANLI0/SwitchTether"
private const val AUTHOR_EMAIL = "wutianli@tianli0.top"

/** A row that opens a web page (or writes a mail) in the phone's own app for it. */
@Composable
private fun LinkRow(title: String, summary: String, url: String, position: CardListPosition) {
    val context = LocalContext.current
    val noApp = stringResource(R.string.viewer_no_app)
    ArrowPreference(
        title = title,
        summary = summary,
        cardListPosition = position,
        onClick = {
            val action = if (url.startsWith("mailto:")) android.content.Intent.ACTION_SENDTO else android.content.Intent.ACTION_VIEW
            runCatching { context.startActivity(android.content.Intent(action, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { android.widget.Toast.makeText(context, noApp, android.widget.Toast.LENGTH_SHORT).show() }
        },
    )
}

@Composable
private fun AppHeader() {
    val context = LocalContext.current
    val icon = remember {
        runCatching {
            val d = context.packageManager.getApplicationIcon(context.packageName)
            val px = (72 * context.resources.displayMetrics.density).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, px, px)
            d.draw(c)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.app_name), style = COUITheme.textStyles.title3, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = COUITheme.textStyles.footnote1,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
