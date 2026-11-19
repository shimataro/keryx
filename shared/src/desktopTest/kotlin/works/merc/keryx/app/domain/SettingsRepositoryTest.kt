package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
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

/** Fails the first `global_settings` write it sees, as a DB failure (e.g. a locked database) would. */
private class FailFirstGlobalWriteDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private val failed = AtomicBoolean(false)

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (sql.contains("global_settings") && failed.compareAndSet(false, true)) {
            throw IllegalStateException("database is locked")
        }
        return delegate.execute(identifier, sql, parameters, binders)
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
    fun closePersistsAPendingSaveAndThenStopsTheWriter() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val store = LocalSettingsStore(dirOverride = dir)
            // A queued dispatcher, so the save below is still pending when close() runs.
            val repo = SettingsRepository(
                db, store, SyncScheduler {}, Clock { 0L },
                writeDispatcher = StandardTestDispatcher(testScheduler),
            )
            repo.saveLocalSettings(LocalSettings(themeMode = "dark"))
            assertFalse(store.isSetupComplete())

            repo.close()
            assertEquals("dark", store.load().themeMode)

            // The writer is gone: a later save stays in memory only.
            repo.saveLocalSettings(LocalSettings(themeMode = "light"))
            advanceUntilIdle()
            assertEquals("light", repo.getLocalSettings().themeMode)
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

    /**
     * A global setter is called from the UI thread (the settings screen), so its DB write must run
     * on the write dispatcher rather than block the caller on the SQLite write lock.
     */
    @Test
    fun globalSettersWriteOnTheWriteDispatcherNotTheCallersThread() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = SettingsCountingSyncScheduler()
            val writes = HoldingDispatcher()
            val repo = SettingsRepository(
                db, LocalSettingsStore(dirOverride = dir), scheduler, Clock { 0L }, writeDispatcher = writes,
            )
            writes.hold()

            repo.setReadTimeoutSeconds(60)
            repo.setCacheRetentionDays(null)
            repo.setArticleListDefaultUnreadOnly(true)

            // Nothing written, nor a sync scheduled, until the write dispatcher runs.
            assertEquals(30, repo.getReadTimeoutSeconds())
            assertEquals(30, repo.getCacheRetentionDays())
            assertFalse(repo.getArticleListDefaultUnreadOnly())
            assertEquals(0, scheduler.callCount)

            writes.release()

            assertEquals(60, repo.getReadTimeoutSeconds())
            assertNull(repo.getCacheRetentionDays())
            assertTrue(repo.getArticleListDefaultUnreadOnly())
            assertEquals(3, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    /** [SettingsRepository.flush] returns only after a global write queued before it has landed. */
    @Test
    fun flushWaitsForAPendingGlobalWrite() {
        val (driver, db) = inMemoryDb()
        try {
            val writes = HoldingDispatcher()
            val repo = SettingsRepository(
                db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = writes,
            )
            writes.hold()
            repo.setReadTimeoutSeconds(60)

            runBlocking {
                var flushed = false
                val flush = launch(start = CoroutineStart.UNDISPATCHED) {
                    repo.flush()
                    flushed = true
                }
                assertFalse(flushed)

                writes.release()
                flush.join()

                assertTrue(flushed)
                assertEquals(60, repo.getReadTimeoutSeconds())
            }
        } finally {
            driver.close()
        }
    }

    /**
     * A failed global write is a DB failure: it surfaces as an uncaught exception on the writer, and
     * the writer's SupervisorJob keeps the next setter's write working.
     */
    @Test
    fun aFailedGlobalWriteDoesNotStopTheNextOne() {
        val (inner, _) = inMemoryDb()
        try {
            val uncaught = mutableListOf<Throwable>()
            val repo = SettingsRepository(
                KeryxDatabase(FailFirstGlobalWriteDriver(inner)), LocalSettingsStore(dirOverride = dir),
                SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined,
                writeExceptionHandler = CoroutineExceptionHandler { _, e -> uncaught += e },
            )

            repo.setReadTimeoutSeconds(60)
            repo.setReadTimeoutSeconds(90)

            assertEquals("database is locked", uncaught.single().message)
            assertEquals(90, repo.getReadTimeoutSeconds())
        } finally {
            inner.close()
        }
    }

    /**
     * A global write is counted as pending from the setter until it has run, so a foreground-exit
     * sync flush made in between waits for the sync it schedules.
     */
    @Test
    fun aGlobalWriteStaysPendingUntilItHasRun() {
        val (driver, db) = inMemoryDb()
        try {
            val writes = HoldingDispatcher()
            val pending = PendingLocalWrites()
            val repo = SettingsRepository(
                db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L },
                writeDispatcher = writes, pendingLocalWrites = pending,
            )
            writes.hold()
            repo.setReadTimeoutSeconds(60)

            runBlocking {
                val idle = launch(start = CoroutineStart.UNDISPATCHED) { pending.awaitIdle() }
                assertFalse(idle.isCompleted)

                writes.release()
                idle.join()
            }
        } finally {
            driver.close()
        }
    }

    /** A failed global write still counts itself finished, or every later flush would wait it out. */
    @Test
    fun aFailedGlobalWriteIsNoLongerPending() {
        val (inner, _) = inMemoryDb()
        try {
            val pending = PendingLocalWrites()
            val repo = SettingsRepository(
                KeryxDatabase(FailFirstGlobalWriteDriver(inner)), LocalSettingsStore(dirOverride = dir),
                SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined,
                writeExceptionHandler = CoroutineExceptionHandler { _, _ -> }, pendingLocalWrites = pending,
            )

            repo.setReadTimeoutSeconds(60)

            runBlocking { withTimeout(1_000L) { pending.awaitIdle() } }
        } finally {
            inner.close()
        }
    }

    /** A global write queued behind close() never runs, and is not left counted either. */
    @Test
    fun aGlobalWriteDroppedByCloseIsNoLongerPending() {
        val (driver, db) = inMemoryDb()
        try {
            val pending = PendingLocalWrites()
            val repo = SettingsRepository(
                db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L },
                pendingLocalWrites = pending,
            )
            runBlocking {
                repo.close()
                repo.setReadTimeoutSeconds(60)

                withTimeout(1_000L) { pending.awaitIdle() }
            }
            assertEquals(30, repo.getReadTimeoutSeconds())
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
