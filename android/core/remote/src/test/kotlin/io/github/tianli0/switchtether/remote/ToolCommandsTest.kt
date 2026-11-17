package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.session.ToolCommands
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCommandsTest {
    @Test fun removesOnlyRecognisedShellLaunchersFromPreview() {
        val original = "\"C:\\Program Files\\WindowsApps\\Codex_1\\pwsh.exe\" -NoProfile -Command \"Get-Content 'D:\\a very long path\\file.kt'\""
        assertEquals("Get-Content 'D:\\a very long path\\file.kt'", ToolCommands.preview(original))
        assertEquals("printf '%s' hello", ToolCommands.preview("/bin/bash -lc \"printf '%s' hello\""))
        assertEquals("git diff -- src/main.rs", ToolCommands.preview("git diff -- src/main.rs"))
        assertEquals("pwsh -File long-script.ps1", ToolCommands.preview("pwsh -File long-script.ps1"))
        assertEquals("echo -Command keep-me", ToolCommands.preview("echo -Command keep-me"))
    }
}
