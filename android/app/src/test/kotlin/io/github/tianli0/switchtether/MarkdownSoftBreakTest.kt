package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.files.joinSoftBreaks
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownSoftBreakTest {
    @Test
    fun continuationLinesLoseTheirIndent() {
        val md = "1. Never write their agent configuration,\n   hooks or plugins.\n"
        assertEquals("1. Never write their agent configuration,\nhooks or plugins.\n", joinSoftBreaks(md))
    }

    @Test
    fun blocksFencesAndIndentedCodeKeepTheirIndent() {
        val md = listOf(
            "- item",
            "  - nested item",
            "",
            "    indented code",
            "```",
            "  code inside",
            "    more",
            "```",
            "Text",
            "  > quote",
        ).joinToString("\n")
        assertEquals(md, joinSoftBreaks(md))
    }
}
