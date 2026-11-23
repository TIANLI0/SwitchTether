package io.github.tianli0.switchtether.files

import androidx.compose.ui.text.SpanStyle
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.ui.files.CodeHighlighting
import io.github.tianli0.switchtether.ui.files.resolveLink
import io.github.tianli0.switchtether.ui.theme.DevColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for the Files page logic: sorting, quick filter, path bar, text document, links, highlighting. */
class FileListingTest {
    private fun f(name: String, size: Long = 0, mtime: Long? = null) = FsEntry(name, "/p/$name", "file", size, mtime)
    private fun d(name: String, mtime: Long? = null) = FsEntry(name, "/p/$name", "dir", 0, mtime)

    @Test
    fun naturalOrderComparesNumbersByValueAndIgnoresCase() {
        val names = listOf("file10.txt", "File2.txt", "file1.txt", "alpha", "Beta")
        val sorted = names.sortedWith { a, b -> FileListing.naturalCompare(a, b) }
        assertEquals(listOf("alpha", "Beta", "file1.txt", "File2.txt", "file10.txt"), sorted)
        assertTrue(FileListing.naturalCompare("a01", "a1") != 0) // never "equal" for different names: stable list
    }

    @Test
    fun foldersComeFirstForEverySortKey() {
        val entries = listOf(f("b.kt", 10, 3), d("zeta", 1), f("a.md", 500, 9), d("Alpha", 5))
        for (key in SortKey.entries) for (desc in listOf(false, true)) {
            val out = FileListing.sort(entries, SortOrder(key, desc))
            assertTrue("$key $desc", out.take(2).all { it.isDir })
        }
        assertEquals(listOf("Alpha", "zeta", "a.md", "b.kt"), FileListing.sort(entries, SortOrder(SortKey.NAME)).map { it.name })
        assertEquals(listOf("zeta", "Alpha", "b.kt", "a.md"), FileListing.sort(entries, SortOrder(SortKey.NAME, descending = true)).map { it.name })
        assertEquals(listOf("Alpha", "zeta", "a.md", "b.kt"), FileListing.sort(entries, SortOrder(SortKey.MODIFIED, descending = true)).map { it.name })
        assertEquals(listOf("Alpha", "zeta", "b.kt", "a.md"), FileListing.sort(entries, SortOrder(SortKey.SIZE)).map { it.name })
        assertEquals(listOf("Alpha", "zeta", "b.kt", "a.md"), FileListing.sort(entries, SortOrder(SortKey.TYPE)).map { it.name })
    }

    @Test
    fun toggledFlipsTheActiveKeyAndStartsNewKeysInTheirNaturalDirection() {
        val byName = SortOrder()
        assertEquals(SortOrder(SortKey.NAME, true), byName.toggled(SortKey.NAME))
        assertEquals(SortOrder(SortKey.MODIFIED, true), byName.toggled(SortKey.MODIFIED)) // newest first
        assertEquals(SortOrder(SortKey.TYPE, false), byName.toggled(SortKey.TYPE))
    }

    @Test
    fun quickFilterMatchesAllTermsAndExtensions() {
        val entries = listOf(f("MainActivity.kt"), f("main.rs"), f("README.md"), d("src"), f(".env"))
        assertEquals(entries, FileListing.filter(entries, "  "))
        assertEquals(listOf("MainActivity.kt", "main.rs"), FileListing.filter(entries, "MAIN").map { it.name })
        assertEquals(listOf("MainActivity.kt"), FileListing.filter(entries, "main activity").map { it.name })
        assertEquals(listOf("main.rs"), FileListing.filter(entries, "*.rs").map { it.name })
        assertEquals(listOf("README.md"), FileListing.filter(entries, ".md").map { it.name })
        assertEquals(listOf(".env"), FileListing.filter(entries, ".env").map { it.name }) // a dot-file name, not an extension
    }

    @Test
    fun breadcrumbsForWindowsPosixAndUncPaths() {
        assertEquals(
            listOf(Crumb("C:", "C:\\"), Crumb("Users", "C:\\Users"), Crumb("me", "C:\\Users\\me")),
            FileListing.breadcrumbs("C:\\Users\\me"),
        )
        assertEquals(listOf(Crumb("home", "/home"), Crumb("me", "/home/me")), FileListing.breadcrumbs("/home/me/"))
        assertEquals(listOf(Crumb("/", "/")), FileListing.breadcrumbs("/"))
        assertEquals(
            listOf(Crumb("share", "\\\\srv\\share"), Crumb("docs", "\\\\srv\\share\\docs")),
            FileListing.breadcrumbs("\\\\srv\\share\\docs"),
        )
        assertTrue(FileListing.breadcrumbs("").isEmpty())
    }

    @Test
    fun breadcrumbsStartAtTheSharedRoot() {
        val roots = listOf(Crumb("SwitchTether", "D:\\develop\\SwitchTether"), Crumb("D:", "D:\\"))
        assertEquals(
            listOf(Crumb("SwitchTether", "D:\\develop\\SwitchTether"), Crumb("android", "D:\\develop\\SwitchTether\\android")),
            FileListing.breadcrumbs("D:\\develop\\SwitchTether\\android", roots),
        )
        // Outside every root: the plain path segments.
        assertEquals(3, FileListing.breadcrumbs("E:\\x\\y", roots).size)
        // Case-insensitive on Windows, separator-aware (SwitchTether2 is not inside SwitchTether).
        assertTrue(FileListing.isInside("D:\\develop\\SwitchTether", "d:\\DEVELOP\\switchtether\\a"))
        assertFalse(FileListing.isInside("D:\\develop\\SwitchTether", "D:\\develop\\SwitchTether2"))
        assertFalse(FileListing.isInside("/home/me", "/home/meow"))
    }

    @Test
    fun relativePathsForSearchResults() {
        assertEquals("src\\main.rs", FileListing.relativeTo("D:\\proj", "D:\\proj\\src\\main.rs"))
        assertEquals("a/b.txt", FileListing.relativeTo("/home/me/", "/home/me/a/b.txt"))
        assertEquals("/elsewhere/x", FileListing.relativeTo("/home/me", "/elsewhere/x"))
        assertEquals("/home/me", FileListing.relativeTo("/home/me", "/home/me"))
    }
}

class FileIconTest {
    @Test
    fun iconsByKindAndExtension() {
        assertEquals(FileIcon.FOLDER, FileTypes.iconOf("src", isDir = true))
        assertEquals(FileIcon.CODE, FileTypes.iconOf("Main.KT", isDir = false))
        assertEquals(FileIcon.MARKDOWN, FileTypes.iconOf("README.md", isDir = false))
        assertEquals(FileIcon.PDF, FileTypes.iconOf("a.pdf", isDir = false))
        assertEquals(FileIcon.IMAGE, FileTypes.iconOf("a.webp", isDir = false))
        assertEquals(FileIcon.ARCHIVE, FileTypes.iconOf("a.tar.gz", isDir = false))
        assertEquals(FileIcon.SHEET, FileTypes.iconOf("data.csv", isDir = false))
        assertEquals(FileIcon.LOCKED, FileTypes.iconOf(".env", isDir = false))
        assertEquals(FileIcon.LOCKED, FileTypes.iconOf("notes.txt", isDir = false, sensitive = true))
        assertEquals(FileIcon.TEXT, FileTypes.iconOf("Makefile", isDir = false))
        assertEquals(FileIcon.OTHER, FileTypes.iconOf("blob.bin", isDir = false))
    }

    @Test
    fun markdownAndNewTextTypes() {
        assertTrue(FileTypes.isMarkdown("C:\\docs\\Guide.MD"))
        assertTrue(FileTypes.isMarkdown("notes.markdown"))
        assertFalse(FileTypes.isMarkdown("md"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("Cargo.lock"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("App.scss"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("fix.patch"))
    }
}

class TextDocumentTest {
    @Test
    fun linesAreIndexedWithoutATrailingEmptyLine() {
        val doc = TextDocument("one\r\ntwo\n\nfour\n")
        assertEquals(4, doc.lineCount)
        assertEquals(listOf("one", "two", "", "four"), (0 until doc.lineCount).map(doc::line))
        assertEquals(1, TextDocument("").lineCount)
        assertEquals("x", TextDocument("x").line(0))
        assertEquals(1, doc.lineOf(doc.lineStart(1)))
        assertEquals(3, doc.lineOf(doc.display.length))
    }

    @Test
    fun tabsAreExpandedAndWideCharactersCountDouble() {
        val doc = TextDocument("\tab\n中文x")
        assertEquals("    ab", doc.line(0))
        assertEquals(6, doc.widestLineCells)
        assertEquals("\tab\n中文x", doc.original)
    }

    @Test
    fun findIsCaseInsensitiveAndReportsLines() {
        val doc = TextDocument("Foo bar\nfoo\nBAR foo")
        val hits = doc.find("foo")
        assertEquals(listOf(0, 1, 2), hits.map { it.line })
        assertEquals("Foo", doc.display.substring(hits[0].start, hits[0].end))
        assertEquals(1, doc.find("foo", matchCase = true).first().line)
        assertTrue(doc.find("").isEmpty())
        assertTrue(doc.find("a\nb").isEmpty())
        assertEquals(2, TextDocument("aaaa").find("aa").size) // non-overlapping
        assertEquals(3, TextDocument("x".repeat(10)).find("x", limit = 3).size)
    }
}

class MarkdownLinkTest {
    @Test
    fun relativeLinksResolveAgainstTheMarkdownFile() {
        assertEquals("D:\\proj\\docs\\SETUP.md", resolveLink("D:\\proj\\README.md", "docs/SETUP.md"))
        assertEquals("D:\\proj\\LICENSE", resolveLink("D:\\proj\\docs\\a.md", "../LICENSE#top"))
        assertEquals("/home/me/proj/src/main.rs", resolveLink("/home/me/proj/README.md", "./src/main.rs"))
        assertEquals("/home/me/proj/a b.md", resolveLink("/home/me/proj/README.md", "a%20b.md"))
        assertEquals("C:/x/y.png", resolveLink("/home/me/README.md", "file:///C:/x/y.png"))
        assertNull(resolveLink("/home/me/README.md", "#section"))
        assertNull(resolveLink("/home/me/README.md", "vscode://open"))
    }
}

class CodeHighlightingTest {
    @Test
    fun libraryLanguagesAreColouredWithTheAppPalette() {
        val colors = DevColors.Light
        val code = "fun main() {\n    val s = \"hi\" // note\n}\n"
        val out = CodeHighlighting.highlight(code, "/p/Main.kt", null, colors)
        assertNotNull(out)
        assertEquals(code, out!!.text)
        val styled = out.spanStyles.map { it.item }
        assertTrue(styled.any { it.color == colors.keyword })
        assertTrue(styled.any { it.color == colors.string })
        assertEquals("kotlin", CodeHighlighting.label("/p/Main.kt", null))
    }

    @Test
    fun fallbackForDataFormatsAndNothingForPlainOrHugeText() {
        val colors = DevColors.Dark
        val json = CodeHighlighting.highlight("{\"a\": true, \"n\": 1}", "/p/x.json", null, colors)
        assertNotNull(json)
        assertTrue(json!!.spanStyles.any { it.item == SpanStyle(color = colors.string) || it.item.color == colors.string })
        assertNull(CodeHighlighting.highlight("just some notes", "/p/notes.txt", null, colors))
        assertNull(CodeHighlighting.highlight("x".repeat(CodeHighlighting.MAX_CHARS + 1), "/p/a.kt", null, colors))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.RUST, CodeHighlighting.libraryLanguage("/p/x.txt", "rust"))
    }
}
