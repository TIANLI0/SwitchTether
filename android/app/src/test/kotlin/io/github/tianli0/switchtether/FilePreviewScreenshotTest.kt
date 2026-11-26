package io.github.tianli0.switchtether

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.coui17.patterns.ToolbarIcon
import io.github.tianli0.switchtether.remote.protocol.FileContent
import io.github.tianli0.switchtether.ui.files.FileHandOffCard
import io.github.tianli0.switchtether.ui.files.FilePreviewHeader
import io.github.tianli0.switchtether.ui.files.LightboxContent
import io.github.tianli0.switchtether.ui.files.LightboxImage
import io.github.tianli0.switchtether.ui.files.TextFileBody
import io.github.tianli0.switchtether.ui.icons.AppIcons
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The file preview's pieces rendered for review into build/outputs/screenshots/files-*.png (zh-CN,
 * API 32): the title bar over a code file, the hand-off card of a file the app cannot show, and the
 * picture lightbox with its controls. Synthetic files only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class FilePreviewScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1200)
        val base = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread {
            val canvas = android.graphics.Canvas(bitmap)
            rootViews().forEach { v ->
                val loc = IntArray(2).also(v::getLocationOnScreen)
                canvas.save()
                canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                v.draw(canvas)
                canvas.restore()
            }
        }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.autoAdvance = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(instance) as List<View>).toList()
    }

    private fun page(mode: ThemeMode, content: @Composable () -> Unit) {
        rule.setContent {
            SwitchTetherTheme(mode) {
                Box(Modifier.fillMaxSize().background(io.github.tianli0.coui17.patterns.pageContainerColor()).statusBarsPadding()) { content() }
            }
        }
    }

    private val code = FileContent(
        path = "D:\\develop\\app\\src\\main\\kotlin\\Main.kt",
        text = (1..60).joinToString("\n") { "fun line$it(x: Int): Int = x * $it // 第 $it 行" },
        size = 3_412,
        language = "kotlin",
    )

    private fun codePage(mode: ThemeMode) = page(mode) {
        Column(Modifier.fillMaxSize()) {
            FilePreviewHeader("Main.kt", "…/src/main/kotlin · 3.3 KB · 60 行", onBack = {}) {
                ToolbarIcon(AppIcons.Refresh, "刷新", {})
                ToolbarIcon(AppIcons.Download, "下载", {})
            }
            TextFileBody(code, Modifier.fillMaxSize())
        }
    }

    @Test
    fun codeFileLight() {
        codePage(ThemeMode.LIGHT)
        rule.onNodeWithText("Main.kt").assertExists()
        shot("files-code-light")
    }

    @Test
    fun codeFileDark() {
        codePage(ThemeMode.DARK)
        shot("files-code-dark")
    }

    @Test
    fun unsupportedFileOffersDownloadAndOpenWith() {
        page(ThemeMode.LIGHT) {
            Column(Modifier.fillMaxSize()) {
                FilePreviewHeader("release-build.zip", "…/dist · 48.2 MB", onBack = {})
                FileHandOffCard(
                    name = "release-build.zip",
                    detail = "48.2 MB · ZIP",
                    message = "此类型没有内置查看器，请用其他应用打开或下载。",
                    primary = "下载到手机" to {},
                    secondary = "用其他应用打开…" to {},
                )
            }
        }
        rule.onNodeWithText("下载到手机").assertExists()
        rule.onNodeWithText("用其他应用打开…").assertExists()
        shot("files-handoff-light")
    }

    @Test
    fun unsupportedFileWhileDownloading() {
        page(ThemeMode.DARK) {
            Column(Modifier.fillMaxSize()) {
                FilePreviewHeader("installer.apk", "…/out · 12.0 MB", onBack = {})
                FileHandOffCard(
                    name = "installer.apk",
                    detail = "12.0 MB · APK",
                    message = "此类型没有内置查看器，请用其他应用打开或下载。",
                    progress = 4_000_000L to 12_000_000L,
                    progressLabel = "正在下载… 3.8 MB / 11.4 MB",
                )
            }
        }
        shot("files-handoff-progress-dark")
    }

    @Test
    fun lightboxWithControls() {
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this)
            c.drawColor(android.graphics.Color.rgb(40, 110, 200))
            val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(250, 200, 60) }
            c.drawCircle(600f, 400f, 260f, p)
        }
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        rule.setContent {
            SwitchTetherTheme(ThemeMode.LIGHT) {
                LightboxContent(
                    images = listOf(LightboxImage("screenshot.png", bytes = bytes), LightboxImage("second.png", bytes = bytes)),
                    startIndex = 0,
                    onDismiss = {},
                    loadBytes = null,
                )
            }
        }
        rule.onNodeWithTag("image-lightbox").assertExists()
        rule.onNodeWithText("screenshot.png").assertExists()
        shot("files-lightbox")
    }
}
