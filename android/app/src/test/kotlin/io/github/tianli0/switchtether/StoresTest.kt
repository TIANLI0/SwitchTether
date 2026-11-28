package io.github.tianli0.switchtether

import androidx.test.core.app.ApplicationProvider
import io.github.tianli0.switchtether.data.DataStoreStores
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoresTest {
    @Test
    fun updateNotifyPrefsIsAtomicUnderConcurrentEdits() = runTest {
        val stores = DataStoreStores(ApplicationProvider.getApplicationContext())
        stores.updateNotifyPrefs { it.copy(mutedAgents = emptySet(), mutedProjects = emptySet()) }

        val jobs = (1..8).map { i ->
            async {
                stores.updateNotifyPrefs { prefs ->
                    prefs.copy(mutedProjects = prefs.mutedProjects + "proj-$i")
                }
            }
        }
        jobs.awaitAll()

        val finalPrefs = stores.notifyPrefs.first()
        assertEquals((1..8).map { "proj-$it" }.toSet(), finalPrefs.mutedProjects)
    }

    @Test
    fun draftStoreSavesClearsAndIsolatesFromSettings() = runTest {
        val stores = DataStoreStores(ApplicationProvider.getApplicationContext())
        stores.clearAll()

        stores.setDraft("sess-1", "hello world")
        assertEquals("hello world", stores.draft("sess-1").first())

        stores.setDraft("sess-1", "   ")
        assertTrue(stores.draft("sess-1").first().isEmpty())

        stores.setDraft("sess-2", "keep")
        stores.clearAll()
        assertTrue(stores.draft("sess-2").first().isEmpty())
    }
}
