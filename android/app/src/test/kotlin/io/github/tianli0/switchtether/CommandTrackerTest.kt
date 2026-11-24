package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.CommandStatus
import io.github.tianli0.switchtether.remote.protocol.CommandRecord
import io.github.tianli0.switchtether.ui.common.CommandPrompt
import io.github.tianli0.switchtether.ui.common.CommandTracker
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CommandTracker and the desktop's command record (§5): `unknown` is not "still running". */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandTrackerTest {
    private fun record(status: String) = CommandRecord("c", status = status)

    @Test
    fun aCommandTheDesktopLostInARestartOffersARetryUnderANewIdOnlyOnTap() = runTest(UnconfinedTestDispatcher()) {
        val status: CommandStatus = CommandStatus.Interrupted(record("unknown"))
        val tracker = CommandTracker(backgroundScope) { status }
        val ids = mutableListOf<String>()
        var succeeded = 0
        var answer: (String) -> CommandOutcome = { CommandOutcome.Unknown(it, IOException("timeout")) }
        tracker.run("Switch provider", onSuccess = { succeeded++ }) { id -> ids += id; answer(id) }
        val first = tracker.prompt.value!!
        assertEquals(CommandPrompt.Kind.UNKNOWN, first.kind)
        assertTrue(first.canCheckStatus)

        tracker.checkStatus()
        val checked = tracker.prompt.value!!
        assertTrue(checked.status is CommandStatus.Interrupted)
        assertFalse(checked.canCheckStatus) // settled: the desktop will never finish it
        assertTrue(checked.retryId != null && checked.retryId != ids.single())
        assertEquals(1, ids.size) // nothing was resent on its own

        answer = { CommandOutcome.Succeeded(it, CommandResponse(JsonNull, false)) }
        tracker.retry()
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertNull(tracker.prompt.value)
        assertEquals(1, succeeded)
    }

    @Test
    fun aStillRunningCommandCanBeCheckedAgainAndOffersNoRetry() = runTest(UnconfinedTestDispatcher()) {
        var status: CommandStatus = CommandStatus.Pending(record("pending"))
        val tracker = CommandTracker(backgroundScope) { status }
        tracker.run("Switch provider") { id -> CommandOutcome.InProgress(id) }
        tracker.checkStatus()
        val p = tracker.prompt.value!!
        assertTrue(p.status is CommandStatus.Pending)
        assertNull(p.retryId)
        assertTrue(p.canCheckStatus) // used to end at "OK": the user could never learn it was lost

        status = CommandStatus.Interrupted(record("unknown")) // the desktop restarted meanwhile
        tracker.checkStatus()
        assertTrue(tracker.prompt.value!!.status is CommandStatus.Interrupted)
        assertTrue(tracker.prompt.value!!.retryId != null)
    }
}
