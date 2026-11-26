package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.LocalWriteFailureKind
import works.merc.keryx.app.core.NotificationText
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
     * A failed global write is reported (not thrown), runs only its own onFailed, and leaves the next
     * setter's write working.
     */
    @Test
    fun aFailedGlobalWriteIsReportedRunsItsOnFailedAndDoesNotStopTheNextOne() {
        val (inner, _) = inMemoryDb()
        try {
            val notifications = NotificationCenter()
            val repo = SettingsRepository(
                KeryxDatabase(FailFirstGlobalWriteDriver(inner)), LocalSettingsStore(dirOverride = dir),
                SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined,
                localWriteFailureReporter = LocalWriteFailureReporter(notifications, Clock { 0L }),
            )
            val failed = mutableListOf<Int>()

            repo.setReadTimeoutSeconds(60) { failed += 60 }
            repo.setReadTimeoutSeconds(90) { failed += 90 }

            assertEquals(listOf(60), failed)
            assertEquals(90, repo.getReadTimeoutSeconds())
            assertEquals(
                NotificationText.LocalWriteFailed(LocalWriteFailureKind.OTHER),
                notifications.items.value.single().text,
            )
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
                pendingLocalWrites = pending,
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

    // --- The v2->v3 tag/folder id re-key, applied to the local settings at construction ---

    /**
     * The remap rows the migration left in `ks_id_remap` rewrite the three local-settings fields
     * holding tag or folder ids — [LocalSettings.lastFilter], [LocalSettings.collapsedFolderIds] and
     * [LocalSettings.expandedTagIds] — as soon as the background load has run.
     * The remap rows are kept so [MergeSql] can reconcile pre-upgrade rows that were renamed before
     * the migration; a second application is a no-op because the old ids are no longer present in the
     * local settings.
     */
    @Test
    fun constructionRewritesTheRekeyedTagAndFolderIdsInTheLocalSettings() {
        val (driver, db) = inMemoryDb()
        try {
            val store = LocalSettingsStore(dirOverride = dir)
            // Persist the pre-migration settings (a tag selection, one collapsed folder, one
            // expanded tag) with a first repository over an empty remap.
            val preset = LocalSettings(
                lastFilter = "tag:t-old",
                collapsedFolderIds = setOf("d-old", "d-kept"),
                expandedTagIds = setOf("t-old", "t-kept"),
            )
            val first = newRepo(db, store = store)
            first.saveLocalSettings(preset)
            runBlocking { first.flush() }

            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "d-old", "d-new")

            val repo = newRepo(db, store = store)

            val settings = repo.getLocalSettings()
            assertEquals("tag:t-new", settings.lastFilter)
            assertEquals(setOf("d-new", "d-kept"), settings.collapsedFolderIds)
            assertEquals(setOf("t-new", "t-kept"), settings.expandedTagIds)
            // The remap rows are kept for merge-time reconciliation: a second repository sees the
            // same values because the old ids are gone from the local settings, so re-applying the
            // map is a no-op.
            assertEquals(
                setOf(
                    Triple(ID_REMAP_KIND_TAG, "t-old", "t-new"),
                    Triple(ID_REMAP_KIND_FOLDER, "d-old", "d-new"),
                ),
                db.sync_bookkeepingQueries.idRemapAll().executeAsList()
                    .map { Triple(it.kind, it.old_id, it.new_id) }
                    .toSet(),
            )
            runBlocking { repo.flush() }
            val again = newRepo(db, store = store)
            assertEquals(settings, again.getLocalSettings())
            // And the persisted file carries the rewritten values.
            assertEquals("tag:t-new", store.load().lastFilter)
        } finally {
            driver.close()
        }
    }

    /** A folder filter remaps through the folder rows, not the tag ones; other values pass through. */
    @Test
    fun constructionRemapsAFolderFilterAndLeavesEveryOtherFilterAlone() {
        val (driver, db) = inMemoryDb()
        try {
            val store = LocalSettingsStore(dirOverride = dir)
            val first = newRepo(db, store = store)
            first.saveLocalSettings(LocalSettings(lastFilter = "folder:d-old"))
            runBlocking { first.flush() }

            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "d-old", "d-new")

            val repo = newRepo(db, store = store)

            assertEquals("folder:d-new", repo.getLocalSettings().lastFilter)
        } finally {
            driver.close()
        }
    }

    /** A [LocalSettingsStore] fake that returns a fixed initial value and records save calls. */
    private class RecordingLocalSettingsStore(
        dir: String,
        private val initial: LocalSettings,
    ) : LocalSettingsStore(dirOverride = dir) {
        // Volatile: a test whose save runs on another thread polls it.
        @Volatile var saveCount = 0
            private set
        var error: Throwable? = null

        override fun load(): LocalSettings = initial
        override fun save(settings: LocalSettings) {
            saveCount++
            error?.let { throw it }
            super.save(settings)
        }
    }

    /** When the remap produces no change, the disk write is skipped entirely. */
    @Test
    fun remapSaveIsSkippedWhenRemapProducesNoChange() {
        val (driver, db) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            val store = RecordingLocalSettingsStore(dir, LocalSettings(lastFilter = "tag:t-new"))

            val repo = newRepo(db, store = store)

            assertEquals("tag:t-new", repo.getLocalSettings().lastFilter)
            assertEquals(0, store.saveCount)
        } finally {
            driver.close()
        }
    }

    /** A save failure of the rewrite is logged and swallowed; the in-memory value still applies. */
    @Test
    fun aFailedSaveOfTheRemappedSettingsIsLoggedNotFatal() {
        val (driver, db) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            val store = RecordingLocalSettingsStore(dir, LocalSettings(lastFilter = "tag:t-old")).apply {
                error = RuntimeException("disk full")
            }

            val repo = newRepo(db, store = store)

            assertEquals("tag:t-new", repo.getLocalSettings().lastFilter)
            assertEquals(1, store.saveCount)
        } finally {
            driver.close()
        }
    }

    /**
     * Construction does not read `ks_id_remap`: the load is the [TagFolderIdRemaps]'s, run on its own
     * dispatcher. Held behind a gate, the settings keep their pre-re-key ids and nothing was read or
     * written; once the load lands, they are rewritten and persisted.
     */
    @Test
    fun constructionDoesNotTouchTheDatabaseAndTheRewriteFollowsTheLoad() = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val remaps = TagFolderIdRemaps(
                {
                    reads++
                    runBlocking { gate.await() }
                    TagFolderIdRemap(tagIds = mapOf("t-old" to "t-new"), folderIds = emptyMap())
                },
                Dispatchers.IO,
            )
            val store = RecordingLocalSettingsStore(dir, LocalSettings(lastFilter = "tag:t-old"))
            val repo = SettingsRepository(db, store, SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined, idRemaps = remaps)

            assertEquals("tag:t-old", repo.getLocalSettings().lastFilter, "nothing is applied before the load")
            assertEquals(0, store.saveCount)

            gate.complete(Unit)
            withTimeout(5_000) { remaps.awaitLoaded() }
            // The collector applies the loaded value on the (inline) writer, right after it is published —
            // on the load's thread, so the in-memory value can be seen before its save has run: wait for both.
            withTimeout(5_000) {
                while (repo.getLocalSettings().lastFilter != "tag:t-new" || store.saveCount == 0) delay(5)
            }

            assertEquals(1, reads)
            assertEquals(1, store.saveCount)
        } finally {
            driver.close()
        }
    }

    // --- lastFilter, through the article filter's own codec ---

    private fun lastFilterAfterRemap(lastFilter: String): String? {
        val (driver, db) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "x-old", "t-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "x-old", "d-new")
            val store = RecordingLocalSettingsStore(dir, LocalSettings(lastFilter = lastFilter))
            return newRepo(db, store = store).getLocalSettings().lastFilter
        } finally {
            driver.close()
        }
    }

    @Test
    fun aTagFilterIsRemappedThroughTheTagMap() = assertEquals("tag:t-new", lastFilterAfterRemap("tag:x-old"))

    @Test
    fun aFolderFilterIsRemappedThroughTheFolderMap() = assertEquals("folder:d-new", lastFilterAfterRemap("folder:x-old"))

    @Test
    fun aFeedFilterIsNeverRemapped() = assertEquals("feed:x-old", lastFilterAfterRemap("feed:x-old"))

    /** An undecodable value is kept as it is, and so is the legacy `unread`, which a re-encode would turn into `all`. */
    @Test
    fun anUndecodableOrLegacyFilterIsKeptAsItIs() {
        assertEquals("garbage:x-old", lastFilterAfterRemap("garbage:x-old"))
        assertEquals("unread", lastFilterAfterRemap("unread"))
    }

    // --- Re-keys published while the app runs (TagFolderIdRemaps) ---

    /**
     * A re-key a merge publishes after construction rewrites the same three fields as the startup
     * pass, in memory and on disk; publishing the same map again changes and writes nothing.
     */
    @Test
    fun aPublishedReKeyRewritesTheLocalSettingsOnceAndIdempotently() = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val store = RecordingLocalSettingsStore(
                dir,
                LocalSettings(
                    lastFilter = "folder:d-old",
                    collapsedFolderIds = setOf("d-old", "d-kept"),
                    expandedTagIds = setOf("t-old"),
                ),
            )
            val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
            val repo = SettingsRepository(db, store, SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined, idRemaps = remaps)
            assertEquals(0, store.saveCount, "nothing to rewrite at construction")

            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "d-old", "d-new")
            remaps.publishFromDb()

            val settings = repo.getLocalSettings()
            assertEquals("folder:d-new", settings.lastFilter)
            assertEquals(setOf("d-new", "d-kept"), settings.collapsedFolderIds)
            assertEquals(setOf("t-new"), settings.expandedTagIds)
            assertEquals(1, store.saveCount)
            assertEquals("folder:d-new", LocalSettingsStore(dirOverride = dir).load().lastFilter)

            // A further publish (another merge, say) that brings nothing new writes nothing.
            remaps.publishFromDb()
            assertEquals(settings, repo.getLocalSettings())
            assertEquals(1, store.saveCount)
        } finally {
            driver.close()
        }
    }

    /**
     * A failed write of the runtime rewrite is logged, not fatal: the in-memory value keeps the new
     * ids and the background writer goes on persisting later saves.
     */
    @Test
    fun aFailedWriteOfAPublishedReKeyDoesNotStopLaterSaves() = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val store = RecordingLocalSettingsStore(dir, LocalSettings(lastFilter = "tag:t-old"))
            val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
            val repo = SettingsRepository(db, store, SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined, idRemaps = remaps)
            store.error = RuntimeException("disk full")

            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            remaps.publishFromDb()

            assertEquals("tag:t-new", repo.getLocalSettings().lastFilter)
            assertEquals(1, store.saveCount)
            store.error = null
            repo.mutateLocalSettings { it.copy(lastFocusedPane = "reader") }
            assertEquals(2, store.saveCount)
            assertEquals("tag:t-new", LocalSettingsStore(dirOverride = dir).load().lastFilter)
        } finally {
            driver.close()
        }
    }
}
