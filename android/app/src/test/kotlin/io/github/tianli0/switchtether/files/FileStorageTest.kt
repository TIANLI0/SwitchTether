package io.github.tianli0.switchtether.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTypesTest {
    @Test
    fun detectsKindAndMimeByExtension() {
        assertEquals(FileKind.PDF, FileTypes.kindOf("C:\\docs\\Report.PDF"))
        assertEquals("application/pdf", FileTypes.mimeFor("a.pdf"))
        assertEquals(FileKind.IMAGE, FileTypes.kindOf("/tmp/shot.PNG"))
        assertEquals("image/jpeg", FileTypes.mimeFor("x.jpeg"))
        assertEquals(FileKind.VIDEO, FileTypes.kindOf("clip.mkv"))
        assertEquals("video/mp4", FileTypes.mimeFor("clip.mp4"))
        assertEquals(FileKind.AUDIO, FileTypes.kindOf("song.flac"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("main.rs"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("archive.zip"))
        assertEquals("application/octet-stream", FileTypes.mimeFor("mystery.bin"))
    }

    @Test
    fun dotFilesAndExtensionlessNamesHaveNoExtension() {
        assertEquals("", FileTypes.extension(".env"))
        assertEquals("", FileTypes.extension("Makefile"))
        assertEquals("", FileTypes.extension("trailing."))
        assertEquals(FileKind.OTHER, FileTypes.kindOf(".env"))
        assertFalse(FileKind.OTHER.isMedia)
        assertTrue(FileKind.VIDEO.isMedia)
    }

    @Test
    fun safeFileNameStripsPathsAndBadCharacters() {
        assertEquals("a.txt", FileTypes.safeFileName("..\\..\\a.txt"))
        assertEquals("a_b.txt", FileTypes.safeFileName("a:b.txt"))
        assertEquals("file", FileTypes.safeFileName("   "))
        assertEquals("file", FileTypes.safeFileName("..."))
        val long = FileTypes.safeFileName("x".repeat(300) + ".pdf")
        assertTrue(long.length <= 120)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun recognisesLocalPathLinksButNotWebLinks() {
        assertTrue(FileTypes.isLocalPath("/home/me/a.pdf"))
        assertTrue(FileTypes.isLocalPath("C:\\proj\\a.png"))
        assertTrue(FileTypes.isLocalPath("D:/proj/a.png"))
        assertTrue(FileTypes.isLocalPath("file:///C:/proj/a%20b.png"))
        assertFalse(FileTypes.isLocalPath("https://example.com/a.pdf"))
        assertFalse(FileTypes.isLocalPath("mailto:a@b.c"))
        assertFalse(FileTypes.isLocalPath("#section"))
        assertFalse(FileTypes.isLocalPath("//cdn.example.com/x"))
        assertFalse(FileTypes.isLocalPath(""))
        assertEquals("C:/proj/a b.png", FileTypes.stripFileScheme("file:///C:/proj/a%20b.png"))
        assertEquals("/home/me/a.pdf", FileTypes.stripFileScheme("file:///home/me/a.pdf"))
    }
    @Test
    fun resolvesChatSourceLinksAndLineNumbersUsingDesktopCwd() {
        assertEquals("D:/project/src/main.rs", FileTypes.resolveChatPath("src/main.rs:120", "D:/project"))
        assertEquals("C:/project/a.kt", FileTypes.resolveChatPath("file:///C:/project/a.kt#L12-L20", null))
        assertEquals("/project/a.kt", FileTypes.resolveChatPath("/project/a.kt:12:3", null))
        assertNull(FileTypes.resolveChatPath("https://example.com/a.pdf", "D:/project"))
        assertNull(FileTypes.resolveChatPath("javascript:alert(1)", "D:/project"))
        assertNull(FileTypes.resolveChatPath("#section", "D:/project"))
        assertNull(FileTypes.resolveChatPath("src/main.rs", null))
    }

}

class FileCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(cache: FileCache, key: String, name: String, bytes: Int): File {
        val p = cache.begin(key, name)
        p.file.writeBytes(ByteArray(bytes))
        return cache.commit(p)
    }

    @Test
    fun keyDependsOnServerPathSizeAndMtime() {
        val a = FileCache.keyFor("srv", "/a.pdf", 10, 5)
        assertEquals(a, FileCache.keyFor("srv", "/a.pdf", 10, 5))
        assertNotEquals(a, FileCache.keyFor("srv2", "/a.pdf", 10, 5))
        assertNotEquals(a, FileCache.keyFor("srv", "/b.pdf", 10, 5))
        assertNotEquals(a, FileCache.keyFor("srv", "/a.pdf", 11, 5))
        assertNotEquals(a, FileCache.keyFor("srv", "/a.pdf", 10, 6))
        assertNotEquals(a, FileCache.keyFor("srv", "/a.pdf", null, null))
    }

    @Test
    fun commitThenGetReturnsTheFileWithItsName() {
        val cache = FileCache(tmp.newFolder("c"))
        val f = put(cache, "k1", "report.pdf", 100)
        assertEquals("report.pdf", f.name)
        assertEquals(f, cache.get("k1"))
        assertNull(cache.get("missing"))
        assertEquals(100, cache.sizeBytes())
    }

    @Test
    fun evictsLeastRecentlyUsedAboveTheCapAndKeepsTheNewestEntry() {
        var now = 1_000L
        val cache = FileCache(tmp.newFolder("c"), maxBytes = 250, clock = { now })
        put(cache, "old", "a.bin", 100)
        now += 10
        put(cache, "mid", "b.bin", 100)
        now += 10
        assertNotNull(cache.get("old")) // touching makes "old" more recent than "mid"
        now += 10
        put(cache, "new", "c.bin", 100) // 300 > 250: evict the least recently used = "mid"
        assertNull(cache.get("mid"))
        assertNotNull(cache.get("old"))
        assertNotNull(cache.get("new"))
        assertTrue(cache.sizeBytes() <= 250)
    }

    @Test
    fun anEntryLargerThanTheCapIsStillKeptWhileItIsTheNewest() {
        val cache = FileCache(tmp.newFolder("c"), maxBytes = 50)
        put(cache, "big", "big.bin", 200)
        assertNotNull(cache.get("big"))
    }

    @Test
    fun staleCopiesWithoutRemoteMetadataExpire() {
        var now = 1_000L
        val cache = FileCache(tmp.newFolder("c"), clock = { now })
        put(cache, "k", "x.png", 10)
        now += 5_000
        assertNotNull(cache.get("k", maxAgeMs = 10_000))
        now += 20_000
        assertNull(cache.get("k", maxAgeMs = 10_000))
        assertNull(cache.get("k")) // the expired entry was dropped
    }

    @Test
    fun abortedDownloadsLeaveNoTrace() {
        val cache = FileCache(tmp.newFolder("c"))
        val p = cache.begin("k", "x.pdf")
        p.file.writeBytes(ByteArray(10))
        assertEquals(10, cache.sizeBytes()) // counted while in flight
        cache.abort(p)
        assertEquals(0, cache.sizeBytes())
        assertNull(cache.get("k"))
    }

    @Test
    fun concurrentDownloadsOfTheSameFileNeverShareATempFile() {
        val dir = tmp.newFolder("c")
        val cache = FileCache(dir)
        val a = cache.begin("k", "x.bin")
        a.file.writeBytes(ByteArray(10) { 1 })
        val b = cache.begin("k", "x.bin") // must neither delete nor reuse the first temp file
        assertNotEquals(a.file, b.file)
        assertEquals(10, a.file.length())
        b.file.writeBytes(ByteArray(20) { 2 })
        assertTrue(cache.commit(a).readBytes().all { it == 1.toByte() })
        val f = cache.commit(b)
        assertEquals(20, f.length())
        assertTrue(f.readBytes().all { it == 2.toByte() })
        assertEquals(f, cache.get("k"))
        assertEquals(20, cache.sizeBytes()) // no temp file left behind
        assertTrue(dir.listFiles()!!.none { it.isFile })
    }

    @Test
    fun aFailedDownloadDoesNotDisturbAnotherOneOfTheSameFile() {
        val cache = FileCache(tmp.newFolder("c"))
        val a = cache.begin("k", "x.bin")
        val b = cache.begin("k", "x.bin")
        a.file.writeBytes(ByteArray(5))
        b.file.writeBytes(ByteArray(7))
        cache.abort(a)
        assertFalse(a.file.exists())
        assertEquals(7, cache.commit(b).length())
    }

    @Test
    fun tempFilesLeftByACrashedRunAreSweptOnTheNextDownloadOfThatFile() {
        val dir = tmp.newFolder("c")
        File(dir, ".part-k").writeBytes(ByteArray(30)) // old naming
        File(dir, ".part-k-1-dead").writeBytes(ByteArray(30))
        File(dir, ".part-other-1-dead").writeBytes(ByteArray(4))
        val cache = FileCache(dir)
        assertEquals(64, cache.sizeBytes())
        val p = cache.begin("k", "x.bin")
        assertEquals(4, cache.sizeBytes()) // only the other key's leftover remains
        p.file.writeBytes(ByteArray(3))
        cache.commit(p)
        assertEquals(7, cache.sizeBytes())
    }

    @Test
    fun clearRemovesEverything() {
        val cache = FileCache(tmp.newFolder("c"))
        put(cache, "a", "a.bin", 10)
        put(cache, "b", "b.bin", 10)
        cache.clear()
        assertEquals(0, cache.sizeBytes())
        assertNull(cache.get("a"))
    }
}

/** Which local copy may stand in for a file on the computer (the "same name, stale copy" report). */
class FileFreshnessTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun commit(cache: FileCache, key: String, name: String, source: String?, bytes: Int = 4): File {
        val p = cache.begin(key, name, source)
        p.file.writeBytes(ByteArray(bytes))
        return cache.commit(p)
    }

    @Test
    fun sameNameInAnotherFolderIsAnotherFile() {
        assertNotEquals(
            FileCache.keyFor("s", "D:\\a\\notes.md", 10, 1_000),
            FileCache.keyFor("s", "D:\\b\\notes.md", 10, 1_000),
        )
        // The same file changed on the computer (new time, same size) is a different copy too.
        assertNotEquals(FileCache.keyFor("s", "/p/notes.md", 10, 1_000), FileCache.keyFor("s", "/p/notes.md", 10, 2_000))
    }

    @Test
    fun aNewCopyReplacesTheOldCopyOfTheSameRemoteFile() {
        val cache = FileCache(tmp.newFolder("c"))
        commit(cache, "old", "notes.md", "/p/notes.md")
        commit(cache, "other", "notes.md", "/q/notes.md")
        val fresh = commit(cache, "new", "notes.md", "/p/notes.md", bytes = 9)
        assertNull(cache.get("old"))
        assertNotNull(cache.get("other"))
        assertEquals(fresh, cache.latestFor("/p/notes.md")?.file)
        assertEquals(setOf("new", "other"), cache.list().map { it.key }.toSet())
    }

    @Test
    fun theSourceNoteIsNeverTakenForTheCopy() {
        val cache = FileCache(tmp.newFolder("c"))
        val f = commit(cache, "k", "a.pdf", "/p/a.pdf", bytes = 7)
        assertEquals(f, cache.get("k"))
        val listed = cache.list().single()
        assertEquals("a.pdf", listed.name)
        assertEquals("/p/a.pdf", listed.source)
        assertEquals(7, listed.size)
        cache.remove("k")
        assertNull(cache.get("k"))
        assertTrue(cache.list().isEmpty())
    }

    @Test
    fun copiesWithoutASourceStayListedButAreNoFallback() {
        val cache = FileCache(tmp.newFolder("c"))
        commit(cache, "bytes-1", "image.png", null)
        assertEquals(null, cache.list().single().source)
        assertNull(cache.latestFor("/p/image.png"))
    }

    @Test
    fun theFolderListingSaysWhatTheFileIsNow() {
        val listing = io.github.tianli0.switchtether.remote.protocol.FsListing(
            path = "C:\\proj",
            entries = listOf(
                io.github.tianli0.switchtether.remote.protocol.FsEntry(name = "docs", path = "C:\\proj\\docs", kind = "dir"),
                io.github.tianli0.switchtether.remote.protocol.FsEntry(name = "a.md", path = "C:\\proj\\a.md", size = 42, modifiedMs = 9_000),
            ),
        )
        assertEquals(FileStore.RemoteMeta(42, 9_000), FileStore.metaIn(listing, "c:/proj/a.md"))
        assertNull(FileStore.metaIn(listing, "C:\\proj\\docs"))
        assertNull(FileStore.metaIn(listing, "C:\\proj\\gone.md"))
    }

    @Test
    fun parentFoldersForTheListing() {
        assertEquals("C:\\proj", FileTypes.parentOf("C:\\proj\\a.md"))
        assertEquals("C:\\", FileTypes.parentOf("C:\\a.md"))
        assertEquals("/", FileTypes.parentOf("/a.md"))
        assertEquals("/home/me", FileTypes.parentOf("/home/me/a.md"))
        assertNull(FileTypes.parentOf("a.md"))
        assertTrue(FileTypes.samePath("D:\\x\\y.txt", "d:/x/y.txt"))
        assertFalse(FileTypes.samePath("/x/y.txt", "/x/z.txt"))
    }
}

class DownloadIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rec(id: String, path: String = "/p/$id.pdf", server: String = "s", at: Long = 1) =
        DownloadRecord(id, server, path, "$id.pdf", 10, "application/pdf", "content://media/$id", at)

    @Test
    fun persistsAndListsNewestFirst() {
        val file = File(tmp.root, "idx.json")
        DownloadIndex(file).run {
            add(rec("a", at = 1))
            add(rec("b", at = 3))
            add(rec("c", at = 2))
        }
        val reloaded = DownloadIndex(file)
        assertEquals(listOf("b", "c", "a"), reloaded.list().map { it.id })
    }

    @Test
    fun aDownloadStandsInOnlyForTheRemoteFileItWasMadeFrom() {
        val r = DownloadRecord("a", "s", "/p/a.pdf", "a.pdf", 1000, "application/pdf", "content://x/a", savedAtMs = 5_000)
        assertTrue(r.matchesRemote(1000, 4_000))
        assertTrue(r.matchesRemote(null, null))
        assertFalse(r.matchesRemote(1001, 4_000))
        // Rewritten on the computer with the same size after it was downloaded: the old copy is stale.
        assertFalse(r.matchesRemote(1000, 9_000))
        assertFalse(r.matchesRemote(null, 9_000))
    }

    @Test
    fun findsByServerAndRemotePath() {
        val idx = DownloadIndex(File(tmp.root, "idx.json"))
        idx.add(rec("a", path = "/x.pdf", server = "s1", at = 1))
        idx.add(rec("b", path = "/x.pdf", server = "s1", at = 2))
        idx.add(rec("c", path = "/x.pdf", server = "s2", at = 3))
        assertEquals(listOf("b", "a"), idx.find("s1", "/x.pdf").map { it.id })
        assertTrue(idx.find("s1", "/nope").isEmpty())
    }

    @Test
    fun prunesRecordsWhoseFileIsGone() {
        val file = File(tmp.root, "idx.json")
        val idx = DownloadIndex(file)
        idx.add(rec("keep"))
        idx.add(rec("gone"))
        val dropped = idx.prune { it.id == "keep" }
        assertEquals(listOf("gone"), dropped.map { it.id })
        assertEquals(listOf("keep"), DownloadIndex(file).list().map { it.id })
    }

    @Test
    fun removeAndClear() {
        val idx = DownloadIndex(File(tmp.root, "idx.json"))
        idx.add(rec("a"))
        idx.add(rec("b"))
        idx.remove(setOf("a"))
        assertEquals(listOf("b"), idx.list().map { it.id })
        idx.clear()
        assertTrue(idx.list().isEmpty())
    }

    @Test
    fun corruptIndexFileDegradesToEmpty() {
        val file = File(tmp.root, "idx.json")
        file.writeText("{not json")
        val idx = DownloadIndex(file)
        assertTrue(idx.list().isEmpty())
        idx.add(rec("a"))
        assertEquals(1, DownloadIndex(file).list().size)
    }
}
