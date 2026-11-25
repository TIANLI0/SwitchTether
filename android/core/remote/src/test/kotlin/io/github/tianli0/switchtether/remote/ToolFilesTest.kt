package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.session.ToolFiles
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolFilesTest {
    private fun data(json: String) = ProtocolJson.parseToJsonElement(json) as JsonObject

    @Test fun findsTheFileOfReadEditAndWriteCalls() {
        assertEquals("D:\\repo\\src\\main.rs", ToolFiles.path(data("""{"category":"read","name":"Read","input":{"file_path":"D:\\repo\\src\\main.rs"}}""")))
        assertEquals("/repo/a.kt", ToolFiles.path(data("""{"category":"edit","name":"Edit","input":{"file_path":"/repo/a.kt","old_string":"a","new_string":"b"}}""")))
        assertEquals("notes.md", ToolFiles.path(data("""{"category":"read","name":"read","input":{"path":"notes.md"}}""")))
        // Other tools keep their own input, even when it has a `path`.
        assertNull(ToolFiles.path(data("""{"category":"search","name":"Grep","input":{"pattern":"x","path":"/repo"}}""")))
        assertNull(ToolFiles.path(data("""{"category":"read","name":"Read"}""")))
    }

    @Test fun splitsNameAndFolderOnEitherSeparator() {
        assertEquals("main.rs", ToolFiles.name("D:\\repo\\src\\main.rs"))
        assertEquals("D:\\repo\\src", ToolFiles.folder("D:\\repo\\src\\main.rs"))
        assertEquals("a.kt", ToolFiles.name("/repo/a.kt"))
        assertEquals("/repo", ToolFiles.folder("/repo/a.kt"))
        assertEquals("", ToolFiles.folder("notes.md"))
    }

    @Test fun describesThePartThatWasRead() {
        assertEquals("L120–169", ToolFiles.range(data("""{"input":{"file_path":"a","offset":120,"limit":50}}""")))
        assertEquals("L120+", ToolFiles.range(data("""{"input":{"file_path":"a","offset":120}}""")))
        assertEquals("L1–200", ToolFiles.range(data("""{"input":{"file_path":"a","limit":200}}""")))
        assertEquals("p. 3-5", ToolFiles.range(data("""{"input":{"file_path":"a.pdf","pages":"3-5"}}""")))
        assertNull(ToolFiles.range(data("""{"input":{"file_path":"a"}}""")))
    }

    /** Codex reads files with shell commands: the desktop names them in `data.files`. */
    @Test fun codexShellReadsAndPatchesNameTheirFiles() {
        val read = data("""{"category":"read","name":"shell","title":"/repo/src/lib.rs","input":{"command":"sed -n '10,19p' src/lib.rs","cwd":"/repo"},"files":[{"path":"/repo/src/lib.rs","offset":10,"limit":10}]}""")
        assertEquals("/repo/src/lib.rs", ToolFiles.path(read))
        assertEquals("L10–19", ToolFiles.range(read))
        val two = data("""{"category":"read","name":"exec","input":{"command":"cat a b"},"files":[{"path":"/r/a"},{"path":"/r/b","offset":3,"limit":1}]}""")
        assertNull(ToolFiles.path(two))
        assertEquals(listOf(ToolFiles.FileRef("/r/a", null), ToolFiles.FileRef("/r/b", "L3")), ToolFiles.files(two))
        // A shell step that also read a file still offers it.
        val mixed = data("""{"category":"shell","name":"shell","input":{"command":"cat a; make"},"files":[{"path":"/r/a"}]}""")
        assertEquals("/r/a", ToolFiles.path(mixed))
        val patch = data("""{"category":"edit","name":"apply_patch","input":{"files":["/r/x.rs","/r/y.rs"]},"files":[{"path":"/r/x.rs"},{"path":"/r/y.rs"}]}""")
        assertEquals(2, ToolFiles.files(patch).size)
        assertNull(ToolFiles.path(data("""{"category":"shell","name":"shell","input":{"command":"make","path":"/r"}}""")))
    }
}
