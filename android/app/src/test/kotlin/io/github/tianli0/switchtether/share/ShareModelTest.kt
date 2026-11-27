package io.github.tianli0.switchtether.share

import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.ui.sessions.MAX_ATTACHMENTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareModelTest {
    private val notOurs: (String) -> Boolean = { it.startsWith("io.github.tianli0.switchtether") }
    private fun send(type: String?, text: String? = null, subject: String? = null, streams: List<String> = emptyList(), clip: List<String> = emptyList()) =
        RawShare(ShareIntake.ACTION_SEND, type, listOfNotNull(text), subject, streams, clip)
    private fun files(n: Int) = (1..n).map { "content://media/external/images/media/$it" }

    // ---- intent parsing ----

    @Test fun plainTextBecomesText() {
        val c = ShareIntake.parse(send("text/plain", text = "  hello world \n"), notOurs)!!
        assertEquals("hello world", c.text)
        assertTrue(c.items.isEmpty())
    }

    @Test fun browserLinkKeepsTitleAndUrl() {
        val c = ShareIntake.parse(send("text/plain", text = "https://example.com/a", subject = "Example page"), notOurs)!!
        assertEquals("Example page\nhttps://example.com/a", c.text)
        // A subject that is already in the text is not repeated.
        val d = ShareIntake.parse(send("text/plain", text = "Example page https://example.com/a", subject = "Example page"), notOurs)!!
        assertEquals("Example page https://example.com/a", d.text)
    }

    @Test fun subjectAloneIsNotAMessage() {
        val c = ShareIntake.parse(send("image/jpeg", subject = "IMG_1", streams = files(1)), notOurs)!!
        assertNull(c.text)
        assertEquals(1, c.items.size)
        assertEquals("image/jpeg", c.items[0].mimeType)
    }

    @Test fun multipleImagesWithWildcardTypeAndClipDuplicates() {
        val uris = files(3)
        val raw = RawShare(ShareIntake.ACTION_SEND_MULTIPLE, "image/*", streams = uris, clipUris = uris)
        val c = ShareIntake.parse(raw, notOurs)!!
        assertEquals(uris, c.items.map { it.uri })
        assertTrue(c.items.all { it.mimeType == null })
    }

    @Test fun clipDataOnlyStillWorks() {
        val c = ShareIntake.parse(RawShare(ShareIntake.ACTION_SEND, "*/*", clipUris = files(1), clipTexts = listOf("note")), notOurs)!!
        assertEquals(1, c.items.size)
        assertEquals("note", c.text)
    }

    @Test fun ownProviderAndNonContentUrisAreRejectedNotDropped() {
        val raw = send(
            "*/*",
            streams = listOf(
                "content://io.github.tianli0.switchtether.fileprovider/cache/secret.txt",
                "file:///sdcard/x.txt",
                "content://com.android.providers.downloads.documents/document/12",
            ),
        )
        val c = ShareIntake.parse(raw, notOurs)!!
        assertEquals(1, c.items.size)
        assertEquals(2, c.rejected)
        // Nothing usable at all is still reported (the user is told), not silently ignored.
        val only = ShareIntake.parse(send("*/*", streams = listOf("file:///x")), notOurs)!!
        assertTrue(only.isEmpty)
        assertEquals(1, only.rejected)
    }

    @Test fun notAShareOrEmptyIsNull() {
        assertNull(ShareIntake.parse(RawShare("android.intent.action.VIEW", "text/plain", texts = listOf("x")), notOurs))
        assertNull(ShareIntake.parse(send("text/plain", text = "   "), notOurs))
    }

    // ---- attachment limit ----

    @Test fun moreThanTenFilesAreSplitNotDropped() {
        val items = files(14).map { SharedItem(it, null) }
        val plan = planAttachments(items)
        assertEquals(MAX_ATTACHMENTS, plan.accepted.size)
        assertEquals(4, plan.overflow.size)
        assertEquals(items, plan.accepted + plan.overflow)
    }

    @Test fun existingAttachmentsReduceTheRoom() {
        val items = files(5).map { SharedItem(it, null) }
        val plan = planAttachments(items, alreadyAttached = 8)
        assertEquals(2, plan.accepted.size)
        assertEquals(3, plan.overflow.size)
        assertEquals(0, planAttachments(items, alreadyAttached = 12).accepted.size)
        assertEquals(5, planAttachments(items).accepted.size)
    }

    // ---- routing ----

    @Test fun routeDecisions() {
        assertEquals(ShareRoute.ONBOARDING, shareRoute(paired = false, verificationPending = false, online = false))
        assertEquals(ShareRoute.ONBOARDING, shareRoute(paired = true, verificationPending = true, online = true))
        assertEquals(ShareRoute.WAIT_FOR_COMPUTER, shareRoute(paired = true, verificationPending = false, online = false))
        assertEquals(ShareRoute.PICK_TARGET, shareRoute(paired = true, verificationPending = false, online = true))
    }

    @Test fun onlyWaitingSharesAreStaged() {
        val item = SharedItem("content://a/1", null)
        assertFalse(needsStaging(item, ShareRoute.PICK_TARGET))
        assertTrue(needsStaging(item, ShareRoute.WAIT_FOR_COMPUTER))
        assertTrue(needsStaging(item, ShareRoute.ONBOARDING))
        assertFalse(needsStaging(item.copy(persisted = true), ShareRoute.WAIT_FOR_COMPUTER))
        assertFalse(needsStaging(item.copy(cachedPath = "/cache/x"), ShareRoute.WAIT_FOR_COMPUTER))
    }

    @Test fun targetsAreControllableRunningFirst() {
        val idle = SessionSummary(id = "idle", control = "full", liveness = "live", status = "idle", lastActiveAtMs = 300)
        val busy = SessionSummary(id = "busy", control = "full", liveness = "live", status = "busy", lastActiveAtMs = 100)
        val ended = SessionSummary(id = "ended", control = "full", liveness = "ended", lastActiveAtMs = 900)
        val observed = SessionSummary(id = "observed", control = "observe", liveness = "live", status = "busy")
        val terminal = SessionSummary(id = "term", control = "full", liveness = "live", status = "idle", terminalId = "t1", lastActiveAtMs = 200)
        val all = listOf(ended, observed, idle, terminal, busy)
        assertEquals(listOf("busy", "idle", "term", "ended"), shareTargets(all, hasFiles = false).map { it.id })
        // A terminal-attached session takes text only.
        assertEquals(listOf("busy", "idle", "ended"), shareTargets(all, hasFiles = true).map { it.id })
    }

    @Test fun draftMerge() {
        assertEquals("shared", mergeDraft("", " shared "))
        assertEquals("typed", mergeDraft("typed", null))
        assertEquals("typed\n\nshared", mergeDraft("typed  ", "shared"))
        assertEquals("typed shared", mergeDraft("typed shared", "shared"))
    }

    // ---- inbox (routing between picker, new session and a session) ----

    private val content = SharedContent("hi", files(2).map { SharedItem(it, null) })

    @Test fun pickSessionDeliversOnceToThatSessionOnly() {
        val inbox = ShareInbox()
        inbox.receive(content)
        assertEquals(ShareInbox.Stage.Picking, inbox.state.value!!.stage)
        inbox.pickSession("s1")
        assertNull(inbox.takeFor("s2"))
        assertEquals(content, inbox.takeFor("s1"))
        assertNull(inbox.takeFor("s1"))
        assertNull(inbox.state.value)
    }

    @Test fun newSessionGetsFilesButNotTheTextAgain() {
        val inbox = ShareInbox()
        inbox.receive(content)
        inbox.pickNewSession()
        // The sheet closes right before the created session is reported (NewSessionSheet order).
        inbox.newSessionClosed()
        inbox.sessionCreated("new1")
        val got = inbox.takeFor("new1")!!
        assertNull(got.text)
        assertEquals(2, got.items.size)
    }

    @Test fun closingTheSheetReturnsToThePicker() {
        val inbox = ShareInbox()
        inbox.receive(content)
        inbox.pickNewSession()
        inbox.newSessionClosed()
        assertEquals(ShareInbox.Stage.Picking, inbox.state.value!!.stage)
        inbox.pickSession("s1")
        // A session created later from elsewhere does not take this share.
        inbox.sessionCreated("other")
        assertEquals(ShareInbox.Stage.Deliver("s1"), inbox.state.value!!.stage)
    }

    @Test fun textOnlyNewSessionIsDoneAtCreation() {
        val inbox = ShareInbox()
        inbox.receive(SharedContent("just text", emptyList()))
        inbox.pickNewSession()
        inbox.sessionCreated("new1")
        assertNull(inbox.state.value)
    }

    @Test fun sessionCreatedWithoutShareChoiceIsIgnored() {
        val inbox = ShareInbox()
        inbox.receive(content)
        inbox.sessionCreated("x")
        assertEquals(ShareInbox.Stage.Picking, inbox.state.value!!.stage)
    }

    @Test fun reopenPickerAndUnpairedAckAndCancel() {
        val inbox = ShareInbox()
        inbox.receive(content)
        inbox.acknowledgeUnpaired()
        assertTrue(inbox.state.value!!.acknowledgedUnpaired)
        inbox.pickSession("s1")
        inbox.reopenPicker()
        assertEquals(ShareInbox.Stage.Picking, inbox.state.value!!.stage)
        // A newer share replaces the pending one (and its acknowledgement).
        inbox.receive(SharedContent("new", emptyList()))
        assertFalse(inbox.state.value!!.acknowledgedUnpaired)
        inbox.cancel()
        assertNull(inbox.state.value)
    }

    // ---- Direct Share (session shortcut in the system share sheet) ----

    @Test fun shortcutIdNamesTheSession() {
        val raw = send("text/plain", text = "hi").copy(shortcutId = "session:abc")
        assertEquals("abc", ShareIntake.parse(raw, notOurs)!!.shortcutSessionId)
        assertNull(ShareIntake.parse(send("text/plain", text = "hi").copy(shortcutId = "other:abc"), notOurs)!!.shortcutSessionId)
        assertNull(ShareIntake.sessionOfShortcut("session:"))
        assertNull(ShareIntake.sessionOfShortcut(null))
    }

    @Test fun directShareSkipsThePickerOnlyForAControllableListedSession() {
        val live = SessionSummary(id = "a", control = "full", liveness = "live", status = "idle")
        val observed = SessionSummary(id = "b", control = "observe", liveness = "live")
        val term = SessionSummary(id = "t", control = "full", liveness = "live", terminalId = "t1")
        val all = listOf(live, observed, term)
        assertEquals("a", directShareTarget("a", all, hasFiles = true))
        assertNull(directShareTarget("b", all, hasFiles = false)) // not controllable
        assertNull(directShareTarget("gone", all, hasFiles = false)) // not listed any more
        assertEquals("t", directShareTarget("t", all, hasFiles = false))
        assertNull(directShareTarget("t", all, hasFiles = true)) // terminal-attached: text only
        assertNull(directShareTarget(null, all, hasFiles = false))
    }

    @Test fun directShareStageResolvesToSessionOrPicker() {
        val inbox = ShareInbox()
        inbox.receive(content.copy(shortcutSessionId = "a"))
        assertEquals(ShareInbox.Stage.Direct("a"), inbox.state.value!!.stage)
        assertNull(inbox.takeFor("a")) // not before it was checked
        inbox.resolveDirect("a")
        assertEquals(content.copy(shortcutSessionId = "a"), inbox.takeFor("a"))

        inbox.receive(content.copy(shortcutSessionId = "gone"))
        inbox.resolveDirect(null)
        assertEquals(ShareInbox.Stage.Picking, inbox.state.value!!.stage)
    }
}
