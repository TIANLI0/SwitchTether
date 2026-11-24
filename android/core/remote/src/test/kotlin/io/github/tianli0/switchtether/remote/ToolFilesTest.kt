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
}
