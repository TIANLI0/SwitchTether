package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.session.InjectedText
import org.junit.Assert.assertEquals
import org.junit.Test

class InjectedTextTest {
    @Test
    fun stripsIdeBlocksAndKeepsTheirFilesAsContext() {
        val c = InjectedText.clean(
            "<ide_opened_file>The user opened the file d:\\app\\AndroidManifest.xml in the IDE. This may or may not be related.</ide_opened_file>" +
                "<ide_selection>The user selected the lines 3 to 9 from d:\\app\\Main.kt:\nval x = 1</ide_selection>" +
                "<system-reminder>noise</system-reminder>Fix the dialog buttons",
        )
        assertEquals("Fix the dialog buttons", c.text)
        assertEquals(listOf("AndroidManifest.xml", "Main.kt"), c.context.map { it.fileName })
        assertEquals("3-9", c.context[1].lines)
    }

    @Test
    fun plainTextIsUntouched() {
        assertEquals("a < b", InjectedText.clean(" a < b ").text)
    }
}
