package io.github.tianli0.switchtether.ui.sessions

import io.github.tianli0.coui17.patterns.PageDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.tianli0.coui17.patterns.LinkButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.tianli0.coui17.patterns.GroupCard
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.protocol.ModelOptions
import io.github.tianli0.coui17.patterns.PanelSheet
import io.github.tianli0.coui17.patterns.TextInput

/** Effort levels offered when the desktop does not send any (older desktop). */
fun fallbackEfforts(agent: String): List<String> = when (agent) {
    "claude_code", "claude" -> listOf("low", "medium", "high", "xhigh", "max")
    "codex" -> listOf("minimal", "low", "medium", "high", "xhigh")
    else -> emptyList()
}

/** The agent accepts an effort / reasoning level. */
fun supportsEffort(agent: String): Boolean = fallbackEfforts(agent).isNotEmpty()

@Composable
fun effortLabel(e: String): String = when (e) {
    "" , "default" -> stringResource(R.string.model_default)
    "none" -> stringResource(R.string.effort_none)
    "minimal" -> stringResource(R.string.effort_minimal)
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    else -> e
}

/** Short "模型 · 强度" text for a chip or a summary row. */
@Composable
fun modelEffortSummary(model: String?, effort: String?): String =
    listOfNotNull(
        model?.takeIf { it.isNotBlank() && it != "default" }?.let(::shortModelName) ?: stringResource(R.string.model_default),
        effort?.takeIf { it.isNotBlank() && it != "default" }?.let { stringResource(R.string.effort_short, effortLabel(it)) },
    ).joinToString(" · ")

/**
 * Model and effort chooser. The models are the agent's own list (Claude Code's `initialize`,
 * Codex `model/list`), which the desktop reads even with no session running; only an older
 * desktop sends the generic aliases. They are ColorOS radio rows with the agent's description;
 * Claude Code's pinned versions (`claude-opus-4-8`, …) fold under "More versions". `""` is the
 * agent's default. Effort is a stepped slider (drag sideways), shown only when the chosen model
 * has effort levels. [recent] are models typed before; any other can be typed when [allowCustom].
 */
@Composable
fun ModelEffortChooser(
    agent: String,
    options: ModelOptions?,
    model: String,
    effort: String,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
    recent: List<String> = emptyList(),
    allowCustom: Boolean = true,
    enabled: Boolean = true,
) {
    val listed = options?.models.orEmpty()
    val official = listed.firstOrNull { it.id == "default" }
    // Rows: the default (with the agent's own description of it), the main models, then pinned versions.
    val main = listed.filter { it.id != "default" && !isPinnedVersion(it.id) }
    val pinned = listed.filter { isPinnedVersion(it.id) }
    val extra = (recent + listOfNotNull(model.takeIf { it.isNotBlank() }))
        .distinct().filter { r -> listed.none { it.id == r } }
    var showPinned by rememberSaveable(agent) { mutableStateOf(pinned.any { it.id == model }) }
    val selectedModel = listed.firstOrNull { it.id == model } ?: official.takeIf { model.isBlank() }
    // A model that lists its own effort levels decides; an empty list means it has none.
    val efforts = selectedModel?.efforts ?: options?.efforts?.takeIf { it.isNotEmpty() } ?: fallbackEfforts(agent)
    val defaultLabel = stringResource(R.string.model_default)
    // Laid out like a settings page: a heading, the models as one group card, then the rest at the
    // page's 16dp gutter. The caller adds no side padding.
    Column(modifier) {
        SmallTitle(stringResource(R.string.model_title))
        val rows = buildList {
            add(Triple("", official?.label?.takeIf { it != "default" } ?: defaultLabel, official?.description))
            main.forEach { add(Triple(it.id, it.label, it.description)) }
            extra.forEach { add(Triple(it, it, null)) }
            if (showPinned) pinned.forEach { add(Triple(it.id, it.label, it.description)) }
        }
        GroupCard(bottom = if (pinned.isNotEmpty()) 4.dp else 16.dp) {
            rows.forEachIndexed { i, (id, label, description) ->
                RadioButtonPreference(
                    title = label,
                    summary = description,
                    selected = id == model,
                    onClick = { if (enabled) onModel(id) },
                    enabled = enabled,
                    cardListPosition = cardListPositionOf(i, rows.size),
                    modifier = Modifier.testTag("model-${id.ifEmpty { "default" }}"),
                )
            }
        }
        if (pinned.isNotEmpty()) {
            LinkButton(
                text = stringResource(if (showPinned) R.string.model_fewer_versions else R.string.model_more_versions, pinned.size),
                onClick = { showPinned = !showPinned },
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp),
            )
        }
        if (allowCustom) {
            var custom by rememberSaveable(agent) { mutableStateOf("") }
            TextInput(
                value = custom,
                onValueChange = { custom = it; if (it.isNotBlank()) onModel(it.trim()) },
                label = stringResource(R.string.new_session_model_hint),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            )
        }
        if (efforts.isNotEmpty()) {
            EffortSlider(efforts, effort, onEffort, enabled)
        }
    }
}

/** Claude Code's dated / numbered model ids (`claude-opus-4-8`, `claude-haiku-4-5-20251001`). */
private fun isPinnedVersion(id: String): Boolean = id.startsWith("claude-")

/**
 * Effort as a horizontal stepped slider: the agent's default at the left end, then each level
 * from lowest to highest. The level is committed when the finger lifts, so dragging across the
 * stops does not change a running session several times. A tap on the track or on a level's name
 * picks that level directly.
 */
@Composable
fun EffortSlider(efforts: List<String>, effort: String, onEffort: (String) -> Unit, enabled: Boolean = true) {
    val stops = listOf("") + efforts
    val committed = stops.indexOf(effort).coerceAtLeast(0)
    var pos by remember(stops, committed) { mutableFloatStateOf(committed.toFloat()) }
    val index = pos.roundToInt().coerceIn(0, stops.lastIndex)
    val pick: (Int) -> Unit = { i ->
        pos = i.toFloat()
        if (stops[i] != effort) onEffort(stops[i])
    }
    GroupCard {
      Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.effort_title), style = COUITheme.textStyles.body1, color = COUITheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(effortLabel(stops[index]), style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = {
                val i = pos.roundToInt().coerceIn(0, stops.lastIndex)
                pos = i.toFloat()
                if (stops[i] != effort) onEffort(stops[i])
            },
            valueRange = 0f..stops.lastIndex.toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            showKeyPoints = true,
            keyPoints = stops.indices.map { it.toFloat() },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                // The slider itself only follows a drag: a tap lands on the nearest stop.
                .pointerInput(stops, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        if (w <= 0f || stops.size < 2) return@detectTapGestures
                        // The stops sit inside the track's rounded ends (half the track height).
                        val inset = (size.height / 2f).coerceAtMost(w / 4f)
                        val f = ((offset.x - inset) / (w - 2 * inset)).coerceIn(0f, 1f)
                        pick((f * stops.lastIndex).roundToInt().coerceIn(0, stops.lastIndex))
                    }
                }
                .testTag("effort-slider"),
        )
        Row(Modifier.fillMaxWidth()) {
            stops.forEachIndexed { i, e ->
                Text(
                    effortLabel(e),
                    style = COUITheme.textStyles.footnote2,
                    color = if (i == index) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        stops.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled) { pick(i) }
                        .padding(vertical = 4.dp)
                        .testTag("effort-${e.ifEmpty { "default" }}"),
                )
            }
        }
      }
    }
}

/** Sheet on the session page: changes model / effort of the running session right away. */
@Composable
fun ModelEffortSheet(
    show: Boolean,
    agent: String,
    options: ModelOptions?,
    model: String?,
    effort: String?,
    busy: Boolean,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Further run settings shown under model and effort (Claude Code's permission mode). */
    extra: @Composable () -> Unit = {},
    /** Set when the model cannot be changed from here (a session taken over from VS Code): shown instead of the chooser. */
    modelNote: String? = null,
) {
    PanelSheet(show = show, title = stringResource(R.string.model_sheet_title), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (modelNote != null) {
                Text(modelNote, style = COUITheme.textStyles.footnote1, color = COUITheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = PageDefaults.TextGutter).padding(bottom = 12.dp))
            } else ModelEffortChooser(
                agent = agent,
                options = options,
                // The running model is reported by its full name; the chips use the agent's ids.
                model = options?.models?.firstOrNull { m -> model != null && (m.id == model || model.startsWith("claude-${m.id}")) }?.id ?: model.orEmpty(),
                effort = effort.orEmpty(),
                onModel = onModel,
                onEffort = onEffort,
                allowCustom = false,
                enabled = !busy,
            )
            extra()
            Text(
                stringResource(R.string.model_sheet_note),
                style = COUITheme.textStyles.footnote1,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageDefaults.TextGutter),
            )
        }
    }
}

/** `claude-opus-5-5` / `claude-sonnet-4-5-20250929` / `claude-opus-4-8[1m]` → `Opus 5.5` / `Sonnet 4.5` / `Opus 4.8 1M`. */
private val CLAUDE_MODEL = Regex("""^claude-(opus|sonnet|haiku|fable)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?(\[1m])?$""", RegexOption.IGNORE_CASE)

/**
 * A model id short enough for a capsule: Claude ids become family and version; others (aliases such
 * as `opus`, Codex's `gpt-5.1-codex`) are kept, with a known alias capitalised.
 */
fun shortModelName(model: String): String {
    val m = CLAUDE_MODEL.matchEntire(model.trim())
    if (m != null) {
        val family = m.groupValues[1].replaceFirstChar { it.uppercase() }
        val version = listOf(m.groupValues[2], m.groupValues[3]).filter { it.isNotEmpty() }.joinToString(".")
        return listOfNotNull(family, version, "1M".takeIf { m.groupValues[4].isNotEmpty() }).joinToString(" ")
    }
    return when (model.lowercase()) {
        "opus", "sonnet", "haiku", "fable" -> model.replaceFirstChar { it.uppercase() }
        else -> model
    }
}
