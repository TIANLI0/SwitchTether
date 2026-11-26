package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.protocol.AgentEvent
import io.github.tianli0.switchtether.remote.protocol.EventKinds
import io.github.tianli0.switchtether.remote.protocol.GitBranches
import io.github.tianli0.switchtether.remote.protocol.GitCommitDetail
import io.github.tianli0.switchtether.remote.protocol.GitStashes
import io.github.tianli0.switchtether.remote.protocol.GitStatusEntry
import io.github.tianli0.switchtether.remote.protocol.GitWriteBody
import io.github.tianli0.switchtether.remote.protocol.GitWriteResult
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.session.TurnFiles
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFilesAndGitTest {
    private fun fixture(name: String): String {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        val f = File(dir, name)
        assertTrue("fixture missing: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private var n = 0
    private fun ev(kind: String, data: JsonObject = JsonObject(emptyMap())) = AgentEvent(id = "e${n++}", kind = kind, data = data)
    private fun edit(name: String, path: String, status: String = "completed") = ev(EventKinds.TOOL_CALL, buildJsonObject {
        put("name", name)
        put("status", status)
        put("category", "edit")
        putJsonObject("input") { put("file_path", path) }
    })

    @Test
    fun latestTurnCollectsEditsAfterTheLastUserMessage() {
        val events = listOf(
            ev(EventKinds.USER_MESSAGE),
            edit("Edit", "/r/old.kt"),
            ev(EventKinds.USER_MESSAGE),
            edit("Edit", "/r/src/a.kt"),
            edit("Write", "/r/src/b.kt"),
            edit("Edit", "/r/src/a.kt"),
            edit("Edit", "/r/denied.kt", status = "denied"),
            ev(EventKinds.TOOL_CALL, buildJsonObject {
                put("name", "Read")
                put("category", "read")
                putJsonObject("input") { put("file_path", "/r/read-only.kt") }
            }),
            ev(EventKinds.TOOL_CALL, buildJsonObject {
                put("name", "apply_patch")
                put("status", "completed")
                putJsonObject("input") {
                    put("patch", "*** Begin Patch\n*** Update File: src/c.rs\n@@\n-a\n+b\n*** Add File: docs/new.md\n+x\n*** End Patch")
                }
            }),
            ev(EventKinds.FILE_CHANGE, buildJsonObject { put("path", "src/d.rs") }),
            ev(EventKinds.ASSISTANT_MESSAGE),
        )
        assertEquals(listOf("/r/src/a.kt", "/r/src/b.kt", "src/c.rs", "docs/new.md", "src/d.rs"), TurnFiles.latestTurn(events))
        // No user message at all: the whole log is the turn.
        assertEquals(listOf("/r/x"), TurnFiles.latestTurn(listOf(edit("MultiEdit", "/r/x"))))
        assertEquals(emptyList<String>(), TurnFiles.latestTurn(listOf(edit("Edit", "/r/x"), ev(EventKinds.USER_MESSAGE))))
    }

    @Test
    fun unifiedDiffPathsAreRead() {
        assertEquals(listOf("src/x.kt"), TurnFiles.patchPaths("--- a/src/x.kt\n+++ b/src/x.kt\n@@ -1 +1 @@\n"))
        assertEquals(emptyList<String>(), TurnFiles.patchPaths("--- a/gone\n+++ /dev/null\n"))
    }

    @Test
    fun relativizeMapsIntoTheRepository() {
        assertEquals("src/a.kt", TurnFiles.relativize("/home/u/r", "/home/u/r/src/a.kt"))
        assertNull(TurnFiles.relativize("/home/u/r", "/home/u/other/a.kt"))
        assertNull(TurnFiles.relativize("/home/u/r", "/home/u/r2/a.kt"))
        assertEquals("src/a.kt", TurnFiles.relativize("C:\\Work\\R", "c:\\work\\r\\src\\a.kt"))
        // Relative paths are relative to the session folder, which may sit below the root.
        assertEquals("app/src/a.kt", TurnFiles.relativize("/r", "src/a.kt", cwd = "/r/app"))
        assertEquals("lib/x.kt", TurnFiles.relativize("/r", "../lib/x.kt", cwd = "/r/app"))
        assertNull(TurnFiles.relativize("/r", "../../x", cwd = "/r/app"))
        assertEquals("src/a.kt", TurnFiles.relativize("/r", "./src/a.kt"))
    }

    @Test
    fun statusEntryFlagsUnderstandPorcelainDots() {
        val modified = GitStatusEntry(path = "a", index = ".", worktree = "M")
        assertFalse(modified.staged)
        assertTrue(modified.unstaged)
        val staged = GitStatusEntry(path = "a", index = "M", worktree = ".")
        assertTrue(staged.staged)
        assertFalse(staged.unstaged)
        val both = GitStatusEntry(path = "a", index = "M", worktree = "M")
        assertTrue(both.staged && both.unstaged)
        val untracked = GitStatusEntry(path = "a", index = "?", worktree = "?", untracked = true)
        assertFalse(untracked.staged)
        assertTrue(untracked.unstaged)
    }

    @Test
    fun gitWireFormatMatchesTheSharedFixtures() {
        assertEquals("git_write", StepUpScopes.GIT_WRITE)
        val req = ProtocolJson.decodeFromString(GitWriteBody.serializer(), fixture("git_write_request.json"))
        assertEquals(listOf("src/lib.rs", "docs/new file.md"), req.paths)
        assertEquals("fix: thing", req.message)
        // Encoding gives the fixture's fields (nulls are left out, which the desktop reads the same).
        val encoded = ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(GitWriteBody.serializer(), req)).jsonObject
        val expected = JsonObject(ProtocolJson.parseToJsonElement(fixture("git_write_request.json")).jsonObject.filterValues { it != JsonNull })
        assertEquals(expected, encoded)

        val res = ProtocolJson.decodeFromString(GitWriteResult.serializer(), fixture("git_write_result.json"))
        assertEquals("main", res.status.branch)
        assertEquals(1L, res.status.ahead)
        assertEquals("M", res.status.entries.single().worktree)
        assertTrue(res.status.entries.single().unstaged)
        assertEquals(40, res.head?.length)

        val b = ProtocolJson.decodeFromString(GitBranches.serializer(), fixture("git_branches.json"))
        assertEquals("main", b.current)
        assertEquals(listOf("main", "feat/a"), b.local.map { it.name })
        assertEquals("origin/main", b.local.first().upstream)
        assertEquals("origin/other", b.remote.single().name)
        assertEquals(0L, b.local.first().ahead)
        assertFalse(req.amend || req.force)

        val d = ProtocolJson.decodeFromString(GitCommitDetail.serializer(), fixture("git_commit_detail.json"))
        assertEquals("fix: thing", d.subject)
        assertEquals("Longer explanation.", d.body)
        assertEquals(1, d.parents.size)
        assertEquals(listOf("M", "R", "A"), d.files.map { it.status })
        assertEquals("docs/old.md", d.files[1].origPath)
        assertEquals(3L, d.files[0].added)
        assertNull(d.files[2].added)

        val st = ProtocolJson.decodeFromString(GitStashes.serializer(), fixture("git_stashes.json"))
        assertEquals(0, st.stashes.single().index)
        assertEquals("On main: wip", st.stashes.single().message)
    }
}
