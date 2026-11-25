package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.update.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val body = """
        ## SwitchTether 0.1.20

        Desktop and Android. Update both sides.

        ### Codex
        - **Slash commands** from the phone.

        ### Downloads
        - Android: `SwitchTether-0.1.20-android.apk` (signed with the release key)
        - `SHA256SUMS.txt`: the in-app updaters check downloads against it.

        ---

        ### 中文说明
        桌面端和 Android 一起更新。

        **Codex**
        - 手机上可以用斜杠命令。

        **下载**
        - Android：`SwitchTether-0.1.20-android.apk`（正式签名）
        - `SHA256SUMS.txt`：应用内更新会用它校验下载的文件。
    """.trimIndent()

    @Test
    fun english_part_without_title_and_downloads() {
        val en = ReleaseNotes.forDisplay(body, chinese = false)
        assertTrue(en.startsWith("Desktop and Android."))
        assertTrue("### Codex" in en)
        assertFalse("SwitchTether 0.1.20" in en)
        assertFalse("SHA256SUMS" in en)
        assertFalse("中文" in en)
        assertFalse("---" in en)
    }

    @Test
    fun chinese_part_only() {
        val zh = ReleaseNotes.forDisplay(body, chinese = true)
        assertEquals("桌面端和 Android 一起更新。\n\n**Codex**\n- 手机上可以用斜杠命令。", zh)
    }

    @Test
    fun a_body_without_sections_is_kept() {
        assertEquals("Fixes.", ReleaseNotes.forDisplay("Fixes.\n", chinese = true))
    }
}
