package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.SessionCache
import io.github.tianli0.switchtether.domain.ManagementRepository
import io.github.tianli0.switchtether.remote.protocol.EventPage
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.security.AesGcmBlobCipher
import io.github.tianli0.switchtether.security.BlobCipher
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.isAway
import io.github.tianli0.switchtether.ui.common.snapshotTimeLabel
import io.github.tianli0.switchtether.ui.home.HomeViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel
import io.github.tianli0.switchtether.ui.sessions.SessionsViewModel
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Software-key stand-in for the Keystore cipher: the same AES-GCM code with a key held in memory. */
class TestCipher : BlobCipher {
    var key: SecretKey? = null
    private val impl = AesGcmBlobCipher(
        keyFor = { create -> key ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it } else null },
        onDestroy = { key = null },
    )
    override fun seal(plain: ByteArray, aad: ByteArray) = impl.seal(plain, aad)
    override fun open(sealed: ByteArray, aad: ByteArray) = impl.open(sealed, aad)
    override fun destroy() = impl.destroy()
}

fun testCipher(): BlobCipher = TestCipher()

/** Offline snapshot (docs/ANDROID_UI.md §33): store bounds, sealing, per-computer keys, VM transitions, gating. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSnapshotTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val offline = ConnectionState.Offline(TEST_DESKTOP, "no route", null)

    private val noManagement = java.lang.reflect.Proxy.newProxyInstance(
        ManagementRepository::class.java.classLoader, arrayOf(ManagementRepository::class.java),
    ) { _, m, _ ->
        if (m.name == "getChanges") kotlinx.coroutines.flow.emptyFlow<String>() else throw IllegalStateException("backend unavailable")
    } as ManagementRepository

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun tempDir(): File = Files.createTempDirectory("st-snap").toFile()

    private fun allBytesUnder(dir: File): String = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    // --- cipher ---

    @Test
    fun cipherRoundTripsAndRejectsTamperingAndOtherContexts() {
        val c = TestCipher()
        val plain = "titles and folders".toByteArray()
        val sealed = c.seal(plain, "a".toByteArray())!!
        assertEquals(AesGcmBlobCipher.VERSION, sealed[0])
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("titles"))
        assertArrayEquals(plain, c.open(sealed, "a".toByteArray()))
        assertNull("bound to its context", c.open(sealed, "b".toByteArray()))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(c.open(tampered, "a".toByteArray()))
        // Two seals of the same text differ (fresh IV).
        assertFalse(sealed.contentEquals(c.seal(plain, "a".toByteArray())!!))
        c.destroy()
        assertNull("a destroyed key opens nothing", c.open(sealed, "a".toByteArray()))
    }

    // --- store ---

    @Test
    fun theListIsSealedOnDiskAndSurvivesARestart() {
        val dir = tempDir()
        val cipher = testCipher()
        SessionCache(dir, cipher, now = { 42_000L }).saveList("srv", listOf(summary("claude:a").copy(title = "secret-title", cwd = "/home/me/private")))
        val raw = allBytesUnder(dir)
        assertTrue(raw.isNotEmpty())
        assertFalse(raw.contains("secret-title"))
        assertFalse(raw.contains("private"))
        // A new process: read back from disk.
        val again = SessionCache(dir, cipher).loadList("srv")!!
        assertEquals("secret-title", again.items.single().title)
        assertEquals(42_000L, again.savedAtMs)
    }

    @Test
    fun conversationsNeverTouchTheDisk() {
        val dir = tempDir()
        val cache = SessionCache(dir, testCipher())
        cache.saveEvents("srv", "claude:a", listOf(event("e1", 1, "assistant_message", """{"text":"transcript-text"}""", session = "claude:a")), false)
        assertNotNull(cache.loadEvents("srv", "claude:a"))
        assertFalse(allBytesUnder(dir).contains("transcript-text"))
        // A new process has no conversations.
        assertNull(SessionCache(dir, testCipher()).loadEvents("srv", "claude:a"))
    }

    @Test
    fun withoutACipherNothingIsWritten() {
        val dir = tempDir()
        val cache = SessionCache(dir, cipher = null)
        cache.saveList("srv", listOf(summary("claude:a")))
        assertEquals(1, cache.loadList("srv")!!.items.size) // still in memory
        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    @Test
    fun plainTextFilesOfEarlierVersionsAreDeleted() {
        val dir = tempDir()
        val server = File(dir, SessionCache.safe("srv")).apply { mkdirs() }
        File(server, "sessions.json").writeText("[]")
        File(server, "events").mkdirs()
        File(server, "events/x.json").writeText("""{"events":[]}""")
        SessionCache(dir, testCipher()).loadList("srv")
        assertFalse(File(server, "sessions.json").exists())
        assertFalse(File(server, "events").exists())
    }

    @Test
    fun snapshotsAreKeyedByComputer() {
        val dir = tempDir()
        val cipher = testCipher()
        val cache = SessionCache(dir, cipher)
        cache.saveList("srv-a", listOf(summary("claude:a")))
        cache.saveEvents("srv-a", "claude:a", listOf(event("e1", 1, "assistant_message", session = "claude:a")), false)
        assertNull(cache.loadList("srv-b"))
        assertNull(cache.loadEvents("srv-b", "claude:a"))

        // A file moved under another computer does not open (the computer's id is bound in).
        val b = File(dir, SessionCache.safe("srv-b")).apply { mkdirs() }
        File(File(dir, SessionCache.safe("srv-a")), "sessions.bin").copyTo(File(b, "sessions.bin"))
        assertNull(SessionCache(dir, cipher).loadList("srv-b"))

        cache.saveList("srv-b", listOf(summary("codex:b")))
        cache.forget("srv-a")
        assertNull(cache.loadList("srv-a"))
        assertNull(cache.loadEvents("srv-a", "claude:a"))
        assertEquals("codex:b", cache.loadList("srv-b")!!.items.single().id)
    }

    @Test
    fun clearDropsEverythingAndDestroysTheKey() {
        val dir = tempDir()
        val cipher = TestCipher()
        val cache = SessionCache(dir, cipher)
        cache.saveList("srv", listOf(summary("claude:a")))
        cache.saveEvents("srv", "claude:a", listOf(event("e1", 1, "assistant_message", session = "claude:a")), false)
        cache.clear()
        assertNull(cipher.key)
        assertFalse(dir.exists())
        assertNull(cache.loadList("srv"))
        assertEquals(0, cache.conversationCount())
        assertEquals(0L, cache.bytesInMemory())
    }

    @Test
    fun theListIsBoundedInRows() {
        val cache = SessionCache(tempDir(), testCipher(), maxListItems = 3)
        cache.saveList("srv", (1..10).map { summary("s$it") })
        assertEquals(listOf("s1", "s2", "s3"), cache.loadList("srv")!!.items.map { it.id })
    }

    @Test
    fun conversationsAreEvictedLeastRecentlyUsedFirst() {
        val cache = SessionCache(tempDir(), testCipher(), maxConversations = 2)
        val page = listOf(event("e1", 1, "assistant_message"))
        cache.saveEvents("srv", "a", page, false)
        cache.saveEvents("srv", "b", page, false)
        cache.loadEvents("srv", "a") // a was used last
        cache.saveEvents("srv", "c", page, false)
        assertNotNull(cache.loadEvents("srv", "a"))
        assertNull(cache.loadEvents("srv", "b"))
        assertNotNull(cache.loadEvents("srv", "c"))
        assertEquals(2, cache.conversationCount())
    }

    @Test
    fun conversationsAreBoundedInEventsAndBytes() {
        val text = "x".repeat(1_000)
        val events = (1L..50).map { event("e$it", it, "assistant_message", """{"text":"$text"}""") }
        // Per conversation: the newest events only.
        val small = SessionCache(tempDir(), testCipher(), maxEventsPerConversation = 10)
        small.saveEvents("srv", "a", events, false)
        val kept = small.loadEvents("srv", "a")!!
        assertEquals((41L..50).toList(), kept.events.map { it.seq })
        assertTrue(kept.hasMoreBefore)

        // In bytes: ~1.2 KB per event, 20 KB in total.
        val cache = SessionCache(tempDir(), testCipher(), maxBytes = 20_000)
        cache.saveEvents("srv", "big", events, false)
        val big = cache.loadEvents("srv", "big")!!
        assertTrue(big.events.size in 1..19)
        assertEquals(50L, big.events.last().seq)
        assertTrue(big.hasMoreBefore)
        assertTrue(cache.bytesInMemory() <= 20_000)
        // A second conversation pushes the first one out when both do not fit.
        cache.saveEvents("srv", "next", events.takeLast(10), false)
        assertNull(cache.loadEvents("srv", "big"))
        assertNotNull(cache.loadEvents("srv", "next"))
        assertTrue(cache.bytesInMemory() <= 20_000)
    }

    @Test
    fun snapshotTimeShowsTheClockTodayAndTheDateOtherwise() {
        val zone = ZoneId.of("Asia/Shanghai")
        val at = java.time.ZonedDateTime.of(2026, 11, 27, 10, 42, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("10:42", snapshotTimeLabel(at, at + 3_600_000, zone, Locale.US))
        val label = snapshotTimeLabel(at, at + 2 * 86_400_000L, zone, Locale.US)
        assertTrue(label, label.contains("10:42") && label.contains("27"))
    }

    @Test
    fun awayMeansPairedButNotUsable() {
        assertTrue(isAway(offline))
        assertTrue(isAway(ConnectionState.Connecting(TEST_DESKTOP, 1, null)))
        assertFalse(isAway(ONLINE))
        assertFalse(isAway(ConnectionState.Unpaired))
        assertFalse(isAway(ConnectionState.Revoked("PC", "revoked")))
    }

    // --- view models ---

    @Test
    fun theSessionListGoesFromSnapshotToLiveAndBack() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            cached = listOf(summary("claude:old", live = false))
            cachedAtMs = 7_000L
            sessions = listOf(summary("claude:new"))
        }
        val conn = FakeConnection(offline)
        val vm = SessionsViewModel(conn, repo)
        advanceUntilIdle()
        assertEquals(7_000L, vm.state.value.snapshotAtMs)
        assertEquals(listOf("claude:old"), vm.state.value.visible.map { it.id })

        conn.state.value = ONLINE
        advanceUntilIdle()
        assertNull("the live list replaced the snapshot", vm.state.value.snapshotAtMs)
        assertEquals(listOf("claude:new"), vm.state.value.visible.map { it.id })

        val before = System.currentTimeMillis()
        conn.state.value = offline
        advanceUntilIdle()
        val at = vm.state.value.snapshotAtMs
        assertNotNull("the list stays, labelled as a snapshot", at)
        assertTrue(at!! >= before)
        assertEquals(listOf("claude:new"), vm.state.value.visible.map { it.id })
    }

    @Test
    fun homeLabelsItsSnapshotUntilTheFirstLiveLoad() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            cached = listOf(summary("claude:old", live = false))
            cachedAtMs = 9_000L
            sessions = listOf(summary("claude:new"))
        }
        val conn = FakeConnection(ConnectionState.Connecting(TEST_DESKTOP, 1, null))
        val home = HomeViewModel(conn, repo, noManagement)
        advanceUntilIdle()
        assertEquals(9_000L, home.state.value.snapshotAtMs)
        conn.state.value = ONLINE
        advanceUntilIdle()
        assertNull(home.state.value.snapshotAtMs)
        assertEquals(listOf("claude:new"), home.state.value.live.map { it.id })
    }

    @Test
    fun aSnapshotConversationOffersNothingThatNeedsTheComputer() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            // A live session the phone fully controls when online.
            cached = listOf(summary("s1", live = true, control = "full", status = "busy").copy(managed = true))
            cachedPage = EventPage(listOf(event("u1", 1, "user_message", """{"text":"hello"}""")), false)
            cachedAtMs = 5_000L
        }
        val vm = SessionDetailViewModel("s1", FakeConnection(offline), repo, FakeDrafts())
        advanceUntilIdle()
        val st = vm.state.value
        assertEquals(5_000L, st.snapshotAtMs)
        assertTrue(st.events is Loadable.Ready)
        assertFalse(st.online)
        assertFalse(st.canSend)
        assertFalse("a snapshot never queues messages", st.canQueue)
        assertFalse(st.canInterrupt)
        assertFalse(st.canStop)
        assertFalse(st.canChangeModel)
        assertFalse(st.canPickPermission)
        assertFalse(st.canFork)
        assertFalse(st.canUseClaudeTools)
        assertFalse(st.canOfferContinue)
    }

    @Test
    fun aConversationGoesFromSnapshotToLiveAndIsKeptWhenTheConnectionDrops() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            cached = listOf(summary("s1", live = false))
            cachedPage = EventPage(listOf(event("u1", 1, "user_message", """{"text":"old"}""")), false)
            detail = SessionDetail(summary("s1", live = true, control = "full").copy(managed = true), caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED"), emptyList())
            events = listOf(event("u1", 1, "user_message", """{"text":"old"}"""), event("a2", 2, "assistant_message", """{"text":"new"}"""))
        }
        val conn = FakeConnection(offline)
        val vm = SessionDetailViewModel("s1", conn, repo, FakeDrafts())
        advanceUntilIdle()
        assertNotNull(vm.state.value.snapshotAtMs)
        assertEquals(1, vm.state.value.log.size)

        conn.state.value = ONLINE
        advanceUntilIdle()
        assertNull("live data replaced the snapshot", vm.state.value.snapshotAtMs)
        assertEquals(2, vm.state.value.log.size)
        assertTrue(vm.state.value.canSend)

        conn.state.value = offline
        advanceUntilIdle()
        assertNotNull(vm.state.value.snapshotAtMs)
        assertEquals(2, vm.state.value.log.size)
        assertFalse(vm.state.value.canSend)
        assertEquals("handed to the in-memory snapshot", listOf("u1", "a2"), repo.remembered["s1"]?.map { it.id })

        // A resync after reconnecting (same epoch) also ends the snapshot.
        conn.state.value = ONLINE
        advanceUntilIdle()
        repo.refetchFlow.emit("resync")
        advanceUntilIdle()
        assertNull(vm.state.value.snapshotAtMs)
    }

    @Test
    fun aConversationThatIsNotOnThePhoneSaysSoOffline() = runTest(dispatcher) {
        val repo = FakeSessionRepository().apply {
            detail = SessionDetail(summary("s1", live = false), caps(), emptyList())
            events = listOf(event("a1", 1, "assistant_message", """{"text":"hi"}"""))
        }
        val conn = FakeConnection(offline)
        val vm = SessionDetailViewModel("s1", conn, repo, FakeDrafts())
        advanceUntilIdle()
        assertTrue(vm.state.value.notInSnapshot)
        assertTrue(vm.state.value.events is Loadable.Loading)
        conn.state.value = ONLINE
        advanceUntilIdle()
        assertFalse(vm.state.value.notInSnapshot)
        assertEquals(1, vm.state.value.log.size)
    }
}
