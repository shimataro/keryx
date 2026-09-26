package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertGlobalSetting
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [SyncScheduler] fake that counts invocations. */
private class SettingsCountingSyncScheduler : SyncScheduler {
    var callCount = 0
        private set

    override fun scheduleSync() {
        callCount++
    }
}

class SettingsRepositoryTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "settings-repo-test-${Random.nextInt()}")

    @AfterTest
    fun cleanup() {
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    // Unconfined write dispatcher so the background disk write (and flush) run inline, keeping the
    // persistence assertions deterministic without virtual-time plumbing.
    private fun newRepo(
        db: works.merc.keryx.app.data.local.db.KeryxDatabase,
        syncScheduler: SyncScheduler = SyncScheduler {},
        clock: Clock = Clock { 0L },
        store: LocalSettingsStore = LocalSettingsStore(dirOverride = dir),
    ) = SettingsRepository(db, store, syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)

    @Test
    fun getReadTimeoutSecondsDefaultsWhenUnset() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)
            assertEquals(30, repo.getReadTimeoutSeconds())
        } finally {
            driver.close()
        }
    }

    @Test
    fun readTimeoutSecondsRoundTripsAndSchedulesSync() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = SettingsCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler)

            repo.setReadTimeoutSeconds(60)

            assertEquals(60, repo.getReadTimeoutSeconds())
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getCacheRetentionDaysDefaultsWhenUnset() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)
            assertEquals(30, repo.getCacheRetentionDays())
        } finally {
            driver.close()
        }
    }

    @Test
    fun cacheRetentionDaysNullSentinelRoundTripsAsUnlimited() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = SettingsCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler)

            repo.setCacheRetentionDays(null)

            assertEquals("null", db.global_settingsQueries.get("cache_retention_days").executeAsOne())
            assertNull(repo.getCacheRetentionDays())
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun cacheRetentionDaysExplicitValueRoundTrips() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)

            repo.setCacheRetentionDays(7)

            assertEquals(7, repo.getCacheRetentionDays())
        } finally {
            driver.close()
        }
    }

    @Test
    fun cacheRetentionDaysCorruptValueFallsBackToDefault() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertGlobalSetting("cache_retention_days", "not-a-number")
            val repo = newRepo(db)

            assertEquals(30, repo.getCacheRetentionDays())
        } finally {
            driver.close()
        }
    }

    @Test
    fun getArticleListDefaultUnreadOnlyDefaultsFalse() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)
            assertFalse(repo.getArticleListDefaultUnreadOnly())
        } finally {
            driver.close()
        }
    }

    @Test
    fun articleListDefaultUnreadOnlyRoundTrips() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)

            repo.setArticleListDefaultUnreadOnly(true)

            assertTrue(repo.getArticleListDefaultUnreadOnly())
        } finally {
            driver.close()
        }
    }

    @Test
    fun getLocalSettingsAndSaveLocalSettingsRoundTripUpdatesStateFlow() {
        val (driver, db) = inMemoryDb()
        try {
            val store = LocalSettingsStore(dirOverride = dir)
            val repo = newRepo(db, store = store)

            assertEquals("system", repo.getLocalSettings().themeMode)

            val updated = LocalSettings(themeMode = "dark", refreshIntervalMinutes = 15)
            repo.saveLocalSettings(updated)

            // In-memory value updates synchronously.
            assertEquals("dark", repo.getLocalSettings().themeMode)
            assertEquals("dark", repo.localSettings.value.themeMode)
            // Confirm it's actually persisted to the store (disk write is off-thread; flush awaits it).
            runBlocking { repo.flush() }
            assertEquals("dark", store.load().themeMode)
        } finally {
            driver.close()
        }
    }

    @Test
    fun isSetupCompleteDelegatesToStore() {
        val (driver, db) = inMemoryDb()
        try {
            val store = LocalSettingsStore(dirOverride = dir)
            val repo = newRepo(db, store = store)

            assertFalse(repo.isSetupComplete())

            repo.saveLocalSettings(LocalSettings())
            // Setup completion is file existence; flush makes the write durable (as setup does).
            runBlocking { repo.flush() }

            assertTrue(repo.isSetupComplete())
            assertEquals(store.isSetupComplete(), repo.isSetupComplete())
        } finally {
            driver.close()
        }
    }

    @Test
    fun globalSettersScheduleSyncButLocalSettersDoNot() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = SettingsCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler)

            repo.saveLocalSettings(LocalSettings(themeMode = "dark"))
            assertEquals(0, scheduler.callCount)

            repo.setReadTimeoutSeconds(45)
            assertEquals(1, scheduler.callCount)

            repo.setCacheRetentionDays(10)
            assertEquals(2, scheduler.callCount)

            repo.setArticleListDefaultUnreadOnly(true)
            assertEquals(3, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    /**
     * Local settings are written from both the UI thread (theme, pane widths, selection) and
     * background coroutines (cache cleanup, the FTS rebuild gate, the update check), so two writers
     * can each hold a snapshot taken before the other's write. This stages that interleaving
     * deterministically and pins why [SettingsRepository.mutateLocalSettings] exists: it must apply
     * its transform to the value as it stands, not to a snapshot captured earlier.
     */
    @Test
    fun mutateLocalSettingsAppliesToTheCurrentValueSoConcurrentFieldWritesSurvive() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)

            // The hazard, staged: a UI writer reads the settings, a background coroutine records the
            // FTS rebuild time, and only then does the UI writer save its own copy of the snapshot.
            val staleSnapshot = repo.getLocalSettings()
            assertNull(staleSnapshot.lastFtsRebuiltAt)
            repo.mutateLocalSettings { it.copy(lastFtsRebuiltAt = 111L) }
            repo.saveLocalSettings(staleSnapshot.copy(lastArticleId = "a1"))
            assertNull(
                repo.getLocalSettings().lastFtsRebuiltAt,
                "a whole-snapshot save drops a field written after the snapshot was taken — the " +
                    "reason production code must not read-copy-write these settings",
            )

            // The same interleaving through mutateLocalSettings keeps both fields.
            repo.mutateLocalSettings { it.copy(lastFtsRebuiltAt = 222L) }
            repo.mutateLocalSettings { it.copy(lastArticleId = "a2") }

            val result = repo.getLocalSettings()
            assertEquals("a2", result.lastArticleId, "the UI writer's field must be applied")
            assertEquals(
                222L,
                result.lastFtsRebuiltAt,
                "the background writer's field must survive a UI write that raced it",
            )
        } finally {
            driver.close()
        }
    }
}
