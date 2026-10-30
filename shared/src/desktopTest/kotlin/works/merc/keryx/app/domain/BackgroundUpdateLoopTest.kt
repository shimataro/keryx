package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.MILLIS_PER_MINUTE
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests [backgroundUpdateLoop]'s scheduling: the wait before each iteration, the setup gate, and
 * the per-step failure isolation. `RefreshCycleRunner` and the update-check dependencies are
 * deliberately left unbound so those steps throw inside `runMaintenanceStep`; the observable
 * effect used here is the last step, the FTS rebuild, which stamps `lastFtsRebuiltAt`.
 */
class BackgroundUpdateLoopTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "background-update-loop-test-${Random.nextInt()}")
    private val settingsFile = FileIO.join(dir, "local_settings.json")

    @AfterTest
    fun cleanup() {
        FileIO.delete(settingsFile)
    }

    private fun testKoin(db: KeryxDatabase, driver: app.cash.sqldelight.db.SqlDriver): Koin {
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined,
        )
        val activityCenter = ActivityCenter()
        val fts = FtsManager(driver).also { it.createTable() }
        return koinApplication {
            modules(
                module {
                    single { settingsRepository }
                    single { activityCenter }
                    single { fts }
                },
            )
        }.koin
    }

    /** Completes setup (persists local_settings.json) with the given refresh interval. */
    private fun Koin.completeSetup(intervalMinutes: Int) {
        get<SettingsRepository>().mutateLocalSettings { it.copy(refreshIntervalMinutes = intervalMinutes) }
    }

    private fun Koin.ftsRebuiltAt() = get<SettingsRepository>().getLocalSettings().lastFtsRebuiltAt

    private fun TestScope.startLoop(koin: Koin) {
        backgroundScope.launch { backgroundUpdateLoop(koin) }
        runCurrent()
    }

    private suspend fun TestScope.advanceMillis(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun doesNothingWhileSetupIsIncomplete() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val koin = testKoin(db, driver)
            // No local_settings.json: the default interval applies and the gate must skip every step.
            startLoop(koin)

            advanceMillis(10 * 60 * MILLIS_PER_MINUTE)

            assertNull(koin.ftsRebuiltAt())
            assertFalse(FileIO.exists(settingsFile))
        } finally {
            driver.close()
        }
    }

    @Test
    fun zeroIntervalWaitsOneMinuteBeforeTheFirstIteration() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val koin = testKoin(db, driver)
            koin.completeSetup(0)
            startLoop(koin)

            advanceMillis(MILLIS_PER_MINUTE - 1)
            assertNull(koin.ftsRebuiltAt())

            advanceMillis(1)
            assertNotNull(koin.ftsRebuiltAt())
        } finally {
            driver.close()
        }
    }

    @Test
    fun positiveIntervalWaitsTheConfiguredMinutes() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val koin = testKoin(db, driver)
            koin.completeSetup(5)
            startLoop(koin)

            // Well past the 1-minute fallback, still short of the configured 5 minutes.
            advanceMillis(5 * MILLIS_PER_MINUTE - 1)
            assertNull(koin.ftsRebuiltAt())

            advanceMillis(1)
            assertNotNull(koin.ftsRebuiltAt())
        } finally {
            driver.close()
        }
    }

    @Test
    fun failingRefreshAndUpdateStepsDoNotSkipTheFtsRebuild() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val koin = testKoin(db, driver)
            // Interval > 0 so the refresh-cycle step is attempted; its RefreshCycleRunner (and the
            // update check's dependencies) are unbound, so both steps throw.
            koin.completeSetup(1)
            startLoop(koin)

            advanceMillis(MILLIS_PER_MINUTE)

            assertNotNull(koin.ftsRebuiltAt())
        } finally {
            driver.close()
        }
    }
}
