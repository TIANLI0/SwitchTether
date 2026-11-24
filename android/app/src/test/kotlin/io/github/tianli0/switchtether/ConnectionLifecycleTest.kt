package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.data.PairedDesktop
import io.github.tianli0.switchtether.data.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start / stop, revoke and backoff of the connection loop (no network: the loop ends before connecting). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLifecycleTest {
    private class MemoryStore(initial: PairedDesktop?) : PairingStore {
        override val desktop = MutableStateFlow(initial)
        override suspend fun current() = desktop.value
        override suspend fun save(desktop: PairedDesktop) { this.desktop.value = desktop }
        override suspend fun clear() { desktop.value = null }
    }

    @Test
    fun stopRightAfterStartLeavesNoLoopRunning() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var loops = 0
        val m = ConnectionManager(scope, MemoryStore(TEST_DESKTOP), identityLoader = { loops++; null }, onRevoked = { _, _ -> })
        // Foreground and background again before the scope ran anything.
        m.start()
        m.stop()
        scheduler.advanceUntilIdle()
        assertEquals("a loop started after stop()", 0, loops)
        assertEquals(ConnectionState.Unpaired, m.state.value)

        // Several wake-ups while one loop is pending or running start exactly one.
        m.start()
        m.retryNow()
        m.onNetworkAvailable()
        m.start()
        scheduler.advanceUntilIdle()
        assertEquals(1, loops)
        scope.cancel()
    }

    @Test
    fun aRevokeWipesOnceAndKeepsItsReasonThroughLaterWakeUps() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val store = MemoryStore(TEST_DESKTOP)
        val wiped = mutableListOf<String>()
        val m = ConnectionManager(
            scope, store,
            identityLoader = { null }, // the key is gone: the loop revokes before any network use
            onRevoked = { d, _ ->
                wiped += d.serverId
                store.clear()
            },
        )
        m.start()
        val revoked = m.state.value as ConnectionState.Revoked
        assertEquals("DESKTOP-01", revoked.desktopName)
        // The app comes to the foreground, the network changes, the user taps "retry".
        m.start()
        m.onNetworkAvailable()
        m.retryNow()
        assertEquals("the reason must still be there for the onboarding screen", revoked, m.state.value)
        assertEquals(listOf("srv"), wiped)
        // Pairing again starts clean.
        m.reset()
        assertEquals(ConnectionState.Unpaired, m.state.value)
        scope.cancel()
    }

    @Test
    fun neverPairedStaysUnpaired() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val m = ConnectionManager(scope, MemoryStore(null), identityLoader = { null }, onRevoked = { _, _ -> throw AssertionError("nothing to wipe") })
        m.start()
        m.retryNow()
        assertEquals(ConnectionState.Unpaired, m.state.value)
        scope.cancel()
    }

    @Test
    fun backoffStaysWithinBoundsForAnyNumberOfFailures() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val m = ConnectionManager(scope, MemoryStore(null), identityLoader = { null }, onRevoked = { _, _ -> })
        // After a success the next attempt is number 1: half a second to one second.
        repeat(200) { assertTrue(m.backoffMs(1) in 500L..1_000L) }
        val attempts = (1..80).toList() + listOf(0, -1, 1_000, 1_000_000, Int.MAX_VALUE, Int.MIN_VALUE)
        for (attempt in attempts) repeat(50) {
            val ms = m.backoffMs(attempt)
            assertTrue("attempt $attempt -> $ms", ms in 500L..ConnectionManager.MAX_BACKOFF_MS)
        }
        // It grows: the fifth failure in a row waits at least 8 s (half of 16 s, capped at the maximum).
        repeat(50) { assertTrue(m.backoffMs(5) >= ConnectionManager.MAX_BACKOFF_MS / 2) }
        scope.cancel()
    }
}
