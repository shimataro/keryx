package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FAVICON_RETRY_INTERVAL_MS
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaviconMissStoreTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "favicon-miss-test-${Random.nextInt()}")

    @AfterTest
    fun cleanup() {
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private fun newSettings(db: works.merc.keryx.app.data.local.db.KeryxDatabase) =
        SettingsRepository(db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined)

    @Test
    fun theSettingsBackedStoreRemembersAMissAndForgetsIt() {
        val (driver, db) = inMemoryDb()
        try {
            val store = SettingsFaviconMissStore(newSettings(db))
            assertNull(store.missedAt("f1"))

            store.recordMiss("f1", 5_000L)
            assertEquals(5_000L, store.missedAt("f1"))

            store.clearMiss("f1")
            assertNull(store.missedAt("f1"))
        } finally {
            driver.close()
        }
    }

    /** A new process (Android's background refreshes mostly are) still knows the earlier miss. */
    @Test
    fun aMissSurvivesANewProcessReadingTheSameSettingsFile() = kotlinx.coroutines.runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val first = newSettings(db)
            SettingsFaviconMissStore(first).recordMiss("f1", 5_000L)
            first.flush()

            val secondProcess = SettingsFaviconMissStore(newSettings(db))

            assertEquals(5_000L, secondProcess.missedAt("f1"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun recordingAMissDropsThoseThatNoLongerSuppressALookup() {
        val old = mapOf("expired" to 1_000L, "recent" to 1_000L + FAVICON_RETRY_INTERVAL_MS + 1)
        val now = 1_000L + FAVICON_RETRY_INTERVAL_MS

        val after = withFaviconMiss(old, "f1", now)

        // "expired" is exactly one interval old — a lookup is due again, so it no longer needs remembering.
        assertEquals(mapOf("recent" to 1_000L + FAVICON_RETRY_INTERVAL_MS + 1, "f1" to now), after)
    }
}
