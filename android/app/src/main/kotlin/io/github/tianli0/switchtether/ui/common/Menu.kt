package io.github.tianli0.switchtether.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.blur.isBlurEnabled
import io.github.suqi8.coui.kmp.window.WindowListPopup

/** One row of an [AppMenu]. [selected] shows the check mark; [danger] paints the label red. */
data class MenuEntry(
    val text: String,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The app's dropdown menu (docs/ANDROID_UI.md §23.5): a COUI17 list popup whose rows form ONE
 * group, so the list has no divider bars between rows, on frosted glass. The popup lives in its own
 * window, where there is no page to blur, so it is handed a blurred snapshot of the page it opened
 * over ([rememberPopupGlass]); without one (blur off, old Android) it keeps COUI's opaque surface.
 */
@Composable
fun AppMenu(show: Boolean, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val glass = rememberPopupGlass(show)
    WindowListPopup(
        show = show,
        onDismissRequest = onDismiss,
        staticBackdrop = glass?.image,
        staticBackdropWindowSize = glass?.windowSize ?: IntSize.Zero,
    ) {
        ListPopupColumn {
            entries.forEachIndexed { index, entry ->
                val item = remember(entry.text, entry.enabled, entry.danger) { DropdownItem(text = entry.text, enabled = entry.enabled, alert = entry.danger) }
                DropdownImpl(
                    item = item,
                    optionSize = entries.size,
                    isSelected = entry.selected,
                    index = index,
                    // Rows of one group stay contiguous: COUI reserves a divider band only where the group changes.
                    groupKey = MenuGroup,
                    role = Role.Button,
                    onSelectedIndexChange = {
                        onDismiss()
                        entry.onClick()
                    },
                )
            }
        }
    }
}

private object MenuGroup

/** The page under a popup, already blurred, and the size of the window it was captured from. */
class PopupGlass(val image: ImageBitmap, val windowSize: IntSize)

/**
 * While [active], a blurred, downscaled snapshot of this activity's window, for a popup window to
 * paint under its translucent surface. Null until the capture lands (one frame, hidden by the
 * popup's fade-in), when blur is off, or below Android 8. Nothing is captured while inactive.
 */
@Composable
fun rememberPopupGlass(active: Boolean): PopupGlass? {
    val view = LocalView.current
    val enabled = isBlurEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    var glass by remember { mutableStateOf<PopupGlass?>(null) }
    DisposableEffect(active, enabled, view) {
        var disposed = false
        if (active && enabled) {
            // A snapshot from the last time the menu was open would show the wrong page.
            glass = null
            val window = view.context.findActivity()?.window
            val decor = window?.peekDecorView()
            if (window != null && decor != null && decor.width > 0 && decor.height > 0) {
                val size = IntSize(decor.width, decor.height)
                runCatching {
                    val bitmap = Bitmap.createBitmap(
                        (size.width / CAPTURE_DOWNSCALE).coerceAtLeast(1),
                        (size.height / CAPTURE_DOWNSCALE).coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    PixelCopy.request(
                        window,
                        bitmap,
                        { result ->
                            if (!disposed && result == PixelCopy.SUCCESS) {
                                glass = runCatching { PopupGlass(boxBlur(bitmap, BLUR_RADIUS, BLUR_PASSES).asImageBitmap(), size) }.getOrNull()
                            }
                        },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }
        }
        onDispose { disposed = true }
    }
    return glass.takeIf { enabled }
}

/** An eighth of the window is plenty under a blur and keeps the bitmap around 200 KB. */
private const val CAPTURE_DOWNSCALE = 8

/** Three box passes approximate a Gaussian; 4px at 1/8 scale is about a 32px (≈ 10dp) radius each. */
private const val BLUR_RADIUS = 4
private const val BLUR_PASSES = 3

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Separable box blur run [passes] times over an opaque bitmap (the window capture); returns a new bitmap. */
internal fun boxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = source.width
    val h = source.height
    if (radius <= 0 || w < 2 || h < 2) return source
    var src = IntArray(w * h)
    var dst = IntArray(w * h)
    source.getPixels(src, 0, w, 0, 0, w, h)
    repeat(passes) {
        blurPass(src, dst, w, h, radius, horizontal = true)
        blurPass(dst, src, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
}

/** One running-sum pass along rows or columns; edges repeat the border pixel. */
private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        var r = 0
        var g = 0
        var b = 0
        fun at(i: Int): Int {
            val k = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * w + k] else src[k * w + line]
        }
        for (i in -radius..radius) {
            val p = at(i)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            val out = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            if (horizontal) dst[line * w + i] else dst[i * w + line] = out
            val leaving = at(i - radius)
            val entering = at(i + radius + 1)
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
