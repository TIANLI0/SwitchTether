package io.github.tianli0.switchtether.ui.theme

import io.github.suqi8.coui.kmp.theme.COUITheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.tianli0.coui17.patterns.Accent
import io.github.tianli0.coui17.patterns.COUI17Theme

/** SwitchTether brand accent (logo background). */
val BrandBlue = Color(0xFF1F6BFF)

/** Accent in dark theme: same hue, lighter for contrast on near-black. */
val BrandBlueDark = Color(0xFF4A86FF)

/** Appearance preference (Settings → Appearance). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Colors for the high-density developer views (code, diffs, tool output, terminal-like rows) and
 * semantic status colors. Separate from the COUI scheme so normal pages keep the ColorOS look.
 */
@Immutable
data class DevColors(
    val isDark: Boolean,
    val codeBackground: Color,
    val codeText: Color,
    val gutter: Color,
    val addedBackground: Color,
    val addedText: Color,
    val removedBackground: Color,
    val removedText: Color,
    val hunkHeader: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    val neutral: Color,
) {
    companion object {
        val Light = DevColors(
            isDark = false,
            codeBackground = Color(0xFFF4F5F7),
            codeText = Color(0xFF1F2328),
            gutter = Color(0xFF8A9099),
            addedBackground = Color(0xFFDDF4E4),
            addedText = Color(0xFF116329),
            removedBackground = Color(0xFFFDE3E1),
            removedText = Color(0xFF9A1C14),
            hunkHeader = Color(0xFF5B6CC4),
            keyword = Color(0xFFAA3DB5),
            string = Color(0xFF0A7A3F),
            comment = Color(0xFF7A808A),
            number = Color(0xFF0B5CAD),
            ok = Color(0xFF1E8E3E),
            warn = Color(0xFFB26A00),
            danger = Color(0xFFD93025),
            info = Color(0xFF1F6BFF),
            neutral = Color(0xFF6B7280),
        )
        val Dark = DevColors(
            isDark = true,
            codeBackground = Color(0xFF1B1D21),
            codeText = Color(0xFFE6E8EB),
            gutter = Color(0xFF7D838C),
            addedBackground = Color(0xFF16351F),
            addedText = Color(0xFF7EE2A1),
            removedBackground = Color(0xFF3D1A1A),
            removedText = Color(0xFFFF9C94),
            hunkHeader = Color(0xFF9DA8F0),
            keyword = Color(0xFFE09CF0),
            string = Color(0xFF8BD8A8),
            comment = Color(0xFF8A9099),
            number = Color(0xFF8CC4FF),
            ok = Color(0xFF5CCB7D),
            warn = Color(0xFFF2B24C),
            danger = Color(0xFFFF7A70),
            info = Color(0xFF4D9AFF),
            neutral = Color(0xFF9AA1AB),
        )
    }
}

val LocalDevColors = staticCompositionLocalOf { DevColors.Light }

/** Monospace styles for dense developer content. */
object DevType {
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    val codeSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
}

/**
 * App theme: the COUI17 library's `COUI17Theme` (ColorOS look, the brand accent in every accent
 * slot, system bars matched to the theme) + developer colors.
 *
 * ColorOS 17 glass materials (title-bar buttons, dialogs, sheets, the navigation capsule) use
 * coui17's blur; the library falls back to flat fills below Android 13 or with system blur off.
 * `COUI17Theme` keeps the Oplus-only native materials off (`platformMaterials = false`), so the
 * library never reflects into private `com.oplus.*` framework classes; the portable Compose blur
 * engine is used everywhere (see third_party/coui17/UPSTREAM.md).
 */
@Composable
fun SwitchTetherTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    COUI17Theme(
        mode = when (mode) {
            ThemeMode.SYSTEM -> io.github.tianli0.coui17.patterns.ThemeMode.System
            ThemeMode.LIGHT -> io.github.tianli0.coui17.patterns.ThemeMode.Light
            ThemeMode.DARK -> io.github.tianli0.coui17.patterns.ThemeMode.Dark
        },
        accent = BrandAccent,
    ) {
        // One red: the developer palette's danger is the COUI scheme's error colour, the one the
        // library's own danger buttons and links use.
        val dev = (if (dark) DevColors.Dark else DevColors.Light).copy(danger = COUITheme.colorScheme.error)
        CompositionLocalProvider(LocalDevColors provides dev, content = content)
    }
}

/** The brand accent in both themes, for `COUI17Theme`. */
val BrandAccent = Accent(light = BrandBlue, dark = BrandBlueDark)
