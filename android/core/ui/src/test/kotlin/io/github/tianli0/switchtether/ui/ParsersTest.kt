package io.github.tianli0.switchtether.ui

import io.github.tianli0.switchtether.ui.diff.DiffLine
import io.github.tianli0.switchtether.ui.diff.UnifiedDiff
import io.github.tianli0.switchtether.ui.markdown.Align
import io.github.tianli0.switchtether.ui.markdown.MarkdownParser
import io.github.tianli0.switchtether.ui.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun sourceLinksAreClickableWithoutEnablingArbitraryAppSchemes() {
        val links = io.github.tianli0.switchtether.ui.markdown.InlineMarkdown
        assertTrue(links.isSupportedLink("D:/project/a.rs:12"))
        assertTrue(links.isSupportedLink("file:///C:/project/a.kt#L12"))
        assertTrue(links.isSupportedLink("src/main.rs"))
        org.junit.Assert.assertFalse(links.isSupportedLink("javascript:alert(1)"))
        org.junit.Assert.assertFalse(links.isSupportedLink("intent://app"))
        org.junit.Assert.assertFalse(links.isSupportedLink("#section"))
    }

    @Test
    fun blocks() {
        val md = """
            # Title
            Some *text* with `code`.
            continued line

            ```kotlin
            fun main() {}
            ```

            > quoted
            > lines

            - a
            - [x] done
              nested para
            1. one
            2. two

            | Col | Num |
            |:----|----:|
            | a \| b | 1 |

            ---
        """.trimIndent()
        val b = MarkdownParser.parse(md)
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Some *text* with `code`.\ncontinued line"), b[1])
        assertEquals(MdBlock.Code("kotlin", "fun main() {}"), b[2])
        assertTrue(b[3] is MdBlock.Quote)
        val list = b[4] as MdBlock.ListBlock
        assertEquals(false, list.ordered)
        assertEquals(2, list.items.size)
        assertEquals(true, list.items[1].checked)
        val ordered = b[5] as MdBlock.ListBlock
        assertTrue(ordered.ordered)
        assertEquals(2, ordered.items.size)
        val table = b[6] as MdBlock.Table
        assertEquals(listOf("Col", "Num"), table.header)
        assertEquals(listOf(Align.START, Align.END), table.align)
        assertEquals(listOf("a | b", "1"), table.rows.single())
        assertEquals(MdBlock.Rule, b[7])
    }

    @Test
    fun unterminatedFenceKeepsContent() {
        val b = MarkdownParser.parse("```\nstreaming code")
        assertEquals(MdBlock.Code(null, "streaming code"), b.single())
    }

    @Test
    fun markdownImagesAndBoldFallbackWork() {
        val blocks = MarkdownParser.parse("![diagram](data:image/png;base64,AAA)\n\n**中文粗体**：[wiki](https://en.wikipedia.org/wiki/Foo_(bar))")
        assertTrue(blocks[0] is MdBlock.Image)
        val img = blocks[0] as MdBlock.Image
        assertEquals("diagram", img.alt)
        val colors = io.github.tianli0.switchtether.ui.markdown.InlineColors(
            link = androidx.compose.ui.graphics.Color.Blue,
            codeText = androidx.compose.ui.graphics.Color.Black,
            codeBackground = androidx.compose.ui.graphics.Color.LightGray,
        )
        val rendered = io.github.tianli0.switchtether.ui.markdown.InlineMarkdown.render("**中文粗体** and ***both***", colors)
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
    }
}

class UnifiedDiffTest {
    @Test
    fun parsesHunksWithLineNumbers() {
        val diff = "diff --git a/x.kt b/x.kt\nindex 1..2 100644\n--- a/x.kt\n+++ b/x.kt\n@@ -10,3 +10,4 @@ fun f()\n ctx\n-old\n+new\n+added\n ctx2\n\\ No newline at end of file\n"
        val f = UnifiedDiff.parse(diff).single()
        assertEquals("x.kt", f.displayPath)
        assertEquals(2, f.additions)
        assertEquals(1, f.deletions)
        val lines = f.hunks.single().lines
        assertEquals(DiffLine(DiffLine.Type.CONTEXT, "ctx", 10, 10), lines[0])
        assertEquals(DiffLine(DiffLine.Type.DEL, "old", 11, null), lines[1])
        assertEquals(DiffLine(DiffLine.Type.ADD, "new", null, 11), lines[2])
        assertEquals(DiffLine(DiffLine.Type.CONTEXT, "ctx2", 12, 13), lines[4])
        assertEquals(DiffLine.Type.META, lines[5].type)
    }

    @Test
    fun multipleFilesAndNewFile() {
        val diff = "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hello\n--- a/old.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n"
        val files = UnifiedDiff.parse(diff)
        assertEquals(listOf("new.txt", "old.txt"), files.map { it.displayPath })
    }
}
