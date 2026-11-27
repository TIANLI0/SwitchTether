package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.sessions.ComposerGate
import io.github.tianli0.switchtether.ui.sessions.DraftEcho
import io.github.tianli0.switchtether.ui.sessions.DropPayload
import io.github.tianli0.switchtether.ui.sessions.FilesBlock
import io.github.tianli0.switchtether.ui.sessions.IntakeBlock
import io.github.tianli0.switchtether.ui.sessions.MAX_ATTACHMENTS
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.SessionScope
import io.github.tianli0.switchtether.ui.sessions.composerGate
import io.github.tianli0.switchtether.ui.sessions.dropIntoDraft
import io.github.tianli0.switchtether.ui.sessions.newSessionGate
import io.github.tianli0.switchtether.ui.sessions.planIntake
import io.github.tianli0.switchtether.ui.sessions.sessionRowOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drag and drop / keyboard content / paste into a composer: classification, limits, gating (§35). */
class ComposerIntakeTest {
    private val own: (String) -> Boolean = { it.startsWith("io.github.tianli0.switchtether") }
    private val open = ComposerGate(IntakeBlock.NONE)

    private fun uris(n: Int, prefix: String = "content://media/external/images/") = (1..n).map { "$prefix$it" }

    @Test
    fun textAndFilesAreSplitAndLinksAreText() {
        val p = planIntake(
            DropPayload(texts = listOf("hello"), uris = listOf("content://com.other/doc/1", "https://example.com/page"), mimeTypes = listOf("text/plain", "image/png")),
            open, alreadyAttached = 0, isOwnAuthority = own,
        )
        assertEquals("hello\nhttps://example.com/page", p.text)
        assertEquals(listOf("content://com.other/doc/1"), p.accepted.map { it.uri })
        assertNull("several types: the resolver decides", p.accepted.single().mimeType)
        assertEquals(0, p.rejected)
        assertFalse(p.isEmpty)
    }

    @Test
    fun aSingleTypeIsKeptForTheFiles() {
        val p = planIntake(DropPayload(uris = listOf("content://com.other/img/1"), mimeTypes = listOf("image/gif")), open, 0, own)
        assertEquals("image/gif", p.accepted.single().mimeType)
        assertNull(p.text)
    }

    @Test
    fun fileUrisAndOwnProviderUrisAreRefusedAndCounted() {
        val p = planIntake(
            DropPayload(uris = listOf("file:///sdcard/secret.txt", "content://io.github.tianli0.switchtether.files/x", "content://com.other/ok", "content://com.other/ok")),
            open, 0, own,
        )
        assertEquals(listOf("content://com.other/ok"), p.accepted.map { it.uri })
        assertEquals(2, p.rejected)
    }

    @Test
    fun atMostTenFilesPerMessageCountingWhatIsAttached() {
        val twelve = planIntake(DropPayload(uris = uris(12)), open, 0, own)
        assertEquals(MAX_ATTACHMENTS, twelve.accepted.size)
        assertEquals(2, twelve.overflow)
        val some = planIntake(DropPayload(uris = uris(5)), open, alreadyAttached = 8, isOwnAuthority = own)
        assertEquals(2, some.accepted.size)
        assertEquals(3, some.overflow)
        val full = planIntake(DropPayload(uris = uris(1), texts = listOf("still text")), open, alreadyAttached = 10, isOwnAuthority = own)
        assertTrue(full.accepted.isEmpty())
        assertEquals(1, full.overflow)
        assertEquals("still text", full.text)
    }

    @Test
    fun blockedComposerTakesNothing() {
        for (b in listOf(IntakeBlock.OFFLINE_SNAPSHOT, IntakeBlock.OFFLINE, IntakeBlock.NO_CONTROL, IntakeBlock.LOADING)) {
            val p = planIntake(DropPayload(texts = listOf("x"), uris = uris(2)), ComposerGate(b), 0, own)
            assertEquals(b, p.block)
            assertTrue(p.isEmpty)
            assertNull(p.text)
        }
    }

    @Test
    fun filesBlockKeepsTheTextAndCountsTheFiles() {
        val p = planIntake(DropPayload(texts = listOf("note"), uris = uris(3) + "file:///x"), ComposerGate(IntakeBlock.NONE, FilesBlock.TEXT_ONLY), 0, own)
        assertEquals("note", p.text)
        assertTrue(p.accepted.isEmpty())
        assertEquals(3, p.refusedFiles)
        assertEquals(1, p.rejected)
        assertEquals(FilesBlock.TEXT_ONLY, p.filesBlock)
    }

    @Test
    fun nothingUsableIsAnEmptyPlan() {
        val p = planIntake(DropPayload(texts = listOf("  ")), open, 0, own)
        assertTrue(p.isEmpty)
        assertEquals(0, p.rejected)
    }

    private fun state(connection: ConnectionState, send: String = "VERIFIED", control: String = "full", terminal: String? = null, snapshot: Long? = null, loaded: Boolean = true) =
        SessionDetailState(
            sessionId = "s1",
            detail = if (loaded) Loadable.Ready(SessionDetail(summary(control = control).copy(terminalId = terminal), caps(send = send), emptyList())) else Loadable.Loading,
            connection = connection,
            snapshotAtMs = snapshot,
        )

    private val offline = ConnectionState.Offline(TEST_DESKTOP, null, null)

    @Test
    fun composerGateFollowsSendAndAttach() {
        assertEquals(ComposerGate(IntakeBlock.NONE, FilesBlock.NONE), composerGate(state(ONLINE)))
        // Attached through a terminal: text only.
        assertEquals(ComposerGate(IntakeBlock.NONE, FilesBlock.TEXT_ONLY), composerGate(state(ONLINE, terminal = "t1")))
        // Read-only (observe) session.
        assertEquals(IntakeBlock.NO_CONTROL, composerGate(state(ONLINE, send = "BLOCKED", control = "observe")).block)
        // Not loaded yet.
        assertEquals(IntakeBlock.LOADING, composerGate(state(ONLINE, loaded = false)).block)
        // Offline snapshot: restored without capabilities.
        assertEquals(IntakeBlock.OFFLINE_SNAPSHOT, composerGate(state(offline, send = "UNKNOWN", snapshot = 1L)).block)
        assertEquals(IntakeBlock.OFFLINE, composerGate(state(offline, send = "UNKNOWN")).block)
        // Live when the connection dropped: text can wait in the queue, files need the connection.
        val queued = composerGate(state(offline, snapshot = 1L))
        assertEquals(ComposerGate(IntakeBlock.NONE, FilesBlock.OFFLINE), queued)
        assertTrue(queued.acceptsText)
        assertFalse(queued.acceptsFiles)
    }

    @Test
    fun newSessionNeedsTheConnection() {
        assertTrue(newSessionGate(true).acceptsFiles)
        assertEquals(IntakeBlock.OFFLINE, newSessionGate(false).block)
    }

    @Test
    fun droppedTextFollowsWhatWasTyped() {
        assertEquals("dropped", dropIntoDraft("", "dropped"))
        assertEquals("typed\n\ndropped", dropIntoDraft("typed  ", "dropped"))
        assertEquals("typed", dropIntoDraft("typed", null))
    }

    @Test
    fun draftEchoTellsOutsideChangesFromLaggingEchoes() {
        val e = DraftEcho()
        e.sent("a")
        e.sent("ab")
        assertFalse("an echo that has not caught up", e.isOutside("a", fieldText = "ab"))
        assertFalse(e.isOutside("ab", fieldText = "ab"))
        // A picked slash command replaces the draft.
        assertTrue(e.isOutside("/review ", fieldText = "ab"))
        // Send clears the draft even though "" was typed once before.
        e.sent("")
        e.sent("hi")
        assertFalse(e.isOutside("", fieldText = "hi"))
        assertFalse(e.isOutside("hi", fieldText = "hi"))
        assertTrue(e.isOutside("", fieldText = "hi"))
        // Already equal: nothing to replace.
        assertFalse(e.isOutside("same", fieldText = "same"))
    }

    @Test
    fun rowOrderForPreviousAndNextFollowsTheList() {
        val working = summary(id = "w", status = "busy")
        val idle = summary(id = "i", status = "idle")
        val past = summary(id = "p", live = false)
        val all = listOf(past, idle, working)
        assertEquals(listOf("w", "i", "p"), sessionRowOrder(all, SessionScope.ALL, emptySet()).map { it.first })
        assertEquals(listOf("w", "i"), sessionRowOrder(all.filter { it.isLive }, SessionScope.LIVE, emptySet()).map { it.first })
        assertEquals(listOf("p"), sessionRowOrder(listOf(past), SessionScope.HISTORY, emptySet()).map { it.first })
    }
}
