package io.github.tianli0.switchtether.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exit-record mapping, the process state summary, bounds, redaction and the ANR excerpt. */
class ExitRecordsTest {
    private fun raw(
        reason: Int = ExitReasons.SIGNALED,
        status: Int = 9,
        importance: Int = 400,
        summary: ByteArray? = null,
        description: String? = null,
        ts: Long = 1_000,
        pid: Int = 42,
        traceBytes: Long? = null,
        traceText: String? = null,
    ) = RawExit(ts, pid, reason, status, importance, 51_200, 102_400, description, summary, traceBytes, traceText)

    @Test
    fun aColorOsBackgroundKillIsMappedWithTheProcessState() {
        val s = ProcessSummary(foreground = false, monitor = true, online = true, transfers = 1).encode()
        val r = mapExit(raw(importance = 125, summary = s, description = "athena kill"))
        assertEquals("SIGNALED", r.reasonName)
        assertEquals("SIGKILL", r.statusName)
        assertEquals("FOREGROUND_SERVICE", r.importanceName)
        assertEquals(MonitorState.RUNNING, r.monitor)
        assertEquals("summary", r.monitorSource)
        assertEquals(false, r.foreground)
        assertEquals(true, r.online)
        assertEquals(1, r.transfers)
        assertEquals("athena kill", r.description)
        assertEquals(51_200, r.pssKb)
        assertEquals(102_400, r.rssKb)
    }

    @Test
    fun withoutASummaryTheMonitorIsInferredOnlyFromAForegroundServiceImportance() {
        assertEquals(MonitorState.RUNNING to "importance", mapExit(raw(importance = 125)).let { it.monitor to it.monitorSource })
        assertEquals(MonitorState.UNKNOWN, mapExit(raw(importance = 400)).monitor)
        val off = mapExit(raw(importance = 125, summary = ProcessSummary(false, false, false, 0).encode()))
        assertEquals("the app's own summary wins", MonitorState.NOT_RUNNING, off.monitor)
        assertNull(mapExit(raw(reason = ExitReasons.LOW_MEMORY, status = 0)).statusName)
        assertEquals("REASON_99", reasonName(99))
        assertEquals("IMPORTANCE_7", importanceName(7))
    }

    @Test
    fun recordsNameTheirProcessAndTheUiProcessNeverHostsTheMonitor() {
        val pkg = "io.github.tianli0.switchtether"
        assertEquals("ui", processLabel(pkg, pkg))
        assertEquals("link", processLabel("$pkg:link", pkg))
        assertNull(processLabel(null, pkg))
        assertNull(processLabel("", pkg))
        assertNull(processLabel("com.other.app", pkg))

        val link = mapExit(raw(importance = 125).copy(process = "link"))
        assertEquals("link", link.process)
        assertEquals(MonitorState.RUNNING, link.monitor)
        // A UI process at foreground-service importance (e.g. a transfer) is not the monitor.
        val ui = mapExit(raw(importance = 125).copy(process = "ui"))
        assertEquals(MonitorState.NOT_RUNNING, ui.monitor)
        assertEquals("process", ui.monitorSource)
        val text = formatReport(listOf(link), emptyList()) { it.toString() }
        assertTrue(text.contains("pid 42  process link"))
    }

    @Test
    fun summaryRoundTripsFitsTheSystemLimitAndRejectsGarbage() {
        val s = ProcessSummary(foreground = true, monitor = false, online = true, transfers = 250)
        val bytes = s.encode()
        assertTrue(bytes.size <= 128)
        assertEquals(s.copy(transfers = 99), ProcessSummary.decode(bytes))
        assertNull(ProcessSummary.decode(null))
        assertNull(ProcessSummary.decode(ByteArray(0)))
        assertNull(ProcessSummary.decode("hello".toByteArray()))
        assertNull(ProcessSummary.decode("st1;fg=2;mon=1".toByteArray()))
        assertNull(ProcessSummary.decode(ByteArray(200) { 'a'.code.toByte() }))
        assertEquals(ProcessSummary(true, true, false, 0), ProcessSummary.decode("st1;fg=1;mon=1".toByteArray()))
    }

    @Test
    fun descriptionsAreRedactedAndBounded() {
        val d = redactDescription(
            "crash in https://10.0.0.5:47615/v1/sessions?id=1 for C:\\Users\\alice\\proj\\a.txt /storage/emulated/0/Download/secret.pdf " +
                "mail alice@example.com ip 192.168.1.20:8080 token sk-ABCDEFGHIJKLMNOPQRSTUVWXyz012345 \"${"x".repeat(60)}\"",
        )!!
        for (leak in listOf("10.0.0.5", "alice", "secret.pdf", "example.com", "192.168.1.20", "ABCDEFGHIJKLMNOP", "xxxxxxxxxx")) {
            assertFalse("$leak leaked: $d", d.contains(leak))
        }
        assertTrue(d.contains("<url>") && d.contains("<path>") && d.contains("<email>") && d.contains("<ip>") && d.contains("<token>"))
        // What the record is for stays readable.
        assertEquals("oplus athena: kill background app (freeze timeout) adj 900", redactDescription("oplus athena: kill background app (freeze timeout) adj 900"))
        assertEquals("android.os.DeadSystemException", redactDescription("android.os.DeadSystemException"))
        assertNull(redactDescription("   "))
        assertEquals(300, redactDescription("a ".repeat(400))!!.length)
    }

    private val anr = """
        ----- pid 4242 at 2026-11-27 10:00:00 -----
        Cmd line: io.github.tianli0.switchtether
        Build fingerprint: 'OPPO/PHZ110/...'

        DALVIK THREADS (40):
        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72e1 self=0xb400
          | sysTid=4242 nice=-10 cgrp=top-app sched=0/0 handle=0x7f
          at io.github.tianli0.switchtether.ui.sessions.SessionDetailViewModel.apply(SessionDetailViewModel.kt:512)
          - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23
          at androidx.compose.runtime.Recomposer.runRecomposeAndApplyChanges(Recomposer.kt:99)
          native: #00 pc 000000000004f3b0  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          user typed: please delete /home/alice/notes.txt
          at java.lang.Thread.run(Thread.java:1012)

        "OkHttp https://192.168.1.20:47615/..." prio=5 tid=23 Native
          at java.net.SocketInputStream.read(SocketInputStream.java:1)
    """.trimIndent()

    @Test
    fun anAnrKeepsOnlyTheMainThreadsFramesRedacted() {
        val r = mapExit(raw(reason = ExitReasons.ANR, status = 0, traceBytes = 123_456, traceText = anr))
        val t = r.trace!!
        assertEquals(123_456L, r.traceBytes)
        assertTrue(t.startsWith("\"main\" prio=5 tid=1 Blocked"))
        assertTrue(t.contains("SessionDetailViewModel.apply(SessionDetailViewModel.kt:512)"))
        assertTrue(t.contains("- waiting to lock"))
        assertTrue("system library paths stay", t.contains("/apex/com.android.runtime/lib64/bionic/libc.so"))
        assertFalse("non-frame lines are withheld", t.contains("user typed"))
        assertFalse(t.contains("alice"))
        assertFalse("other threads are not copied", t.contains("OkHttp"))
        assertFalse(t.contains("Cmd line"))
        assertTrue(t.contains("(1 other lines withheld)"))
    }

    @Test
    fun theAnrExcerptIsCappedAndOnlyAnrsGetOne() {
        val big = "\"main\" prio=5 tid=1 Runnable\n" + (1..2_000).joinToString("\n") { "  at a.b.C.m$it(C.kt:$it)" }
        val t = anrMainThreadExcerpt(big)!!
        assertTrue(t.length <= TRACE_EXCERPT_MAX + 40)
        assertTrue(t.endsWith("(truncated)\n"))
        assertNull("no main thread block", anrMainThreadExcerpt("\"other\" prio=5 tid=2\n  at a.b(C.kt:1)"))
        val native = mapExit(raw(reason = ExitReasons.CRASH_NATIVE, status = 6, traceBytes = 2_048, traceText = anr))
        assertNull("a tombstone is only counted", native.trace)
        assertEquals(2_048L, native.traceBytes)
        assertNull(mapExit(raw(reason = ExitReasons.CRASH, status = 0, traceText = anr)).trace)
    }

    @Test
    fun theLogKeepsTheNewestFiftyOncePerProcess() {
        val existing = (1..45).map { mapExit(raw(ts = it * 1_000L, pid = it)) }
        val incoming = (40..60).map { mapExit(raw(ts = it * 1_000L, pid = it)) }
        val merged = mergeExits(existing, incoming)
        assertEquals(MAX_EXIT_RECORDS, merged.size)
        assertEquals(60_000L, merged.first().timestampMs)
        assertEquals(11_000L, merged.last().timestampMs)
        assertEquals(merged.size, merged.map { it.timestampMs to it.pid }.toSet().size)
        assertEquals(merged, mergeExits(merged, incoming))
    }

    @Test
    fun theReportCarriesTheRecordAndNothingElse() {
        val rec = mapExit(raw(importance = 125, description = "kill by athena /data/user/0/io.github.tianli0.switchtether/files/x"))
        val text = formatReport(listOf(rec), listOf("device" to "OPPO PHZ110")) { "T$it" }
        assertTrue(text.contains("reason: SIGNALED (2)"))
        assertTrue(text.contains("status: 9 SIGKILL"))
        assertTrue(text.contains("monitor service: RUNNING (importance)"))
        assertTrue(text.contains("device: OPPO PHZ110"))
        assertFalse(text.contains("/data/user/0"))
        assertNotNull(rec.description)
    }
}
