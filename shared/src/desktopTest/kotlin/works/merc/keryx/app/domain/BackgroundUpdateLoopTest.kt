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
import works.merc.keryx.app.platform.SelfUpdateCheckSupport
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests [backgroundUpdateLoop]'s scheduling: the wait before each iteration, the setup gate, and
 * the per-step failure isolation. The refresh-cycle and update-check steps resolve bindings that
 * count each attempt and then throw, so a test can tell a step was attempted and failed inside
 * `runMaintenanceStep`; the last step, the FTS rebuild, is observed through `lastFtsRebuiltAt`.
 */
class BackgroundUpdateLoopTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "background-update-loop-test-${Random.nextInt()}")
    private val settingsFile = FileIO.join(dir, "local_settings.json")

    /** How often the refresh-cycle step resolved its [RefreshCycleRunner] — each attempt throws. */
    private val refreshCycleAttempts = AtomicInteger()

    /** How often the update-check step asked [SelfUpdateCheckSupport] — each attempt throws. */
    private val updateCheckAttempts = AtomicInteger()

    @AfterTest
    fun cleanup() {
        File(dir).deleteRecursively()
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
                    factory<RefreshCycleRunner> {
                        refreshCycleAttempts.incrementAndGet()
                        error("refresh cycle failed (test)")
                    }
                    single<SelfUpdateCheckSupport> {
                        SelfUpdateCheckSupport {
                            updateCheckAttempts.incrementAndGet()
                            error("update check failed (test)")
                        }
                    }
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
            assertEquals(0, refreshCycleAttempts.get())
            assertEquals(0, updateCheckAttempts.get())
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
            // A non-positive interval runs maintenance only: the refresh cycle is never attempted.
            assertEquals(0, refreshCycleAttempts.get())
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
            // Interval > 0 so the refresh-cycle step is attempted; it and the update check both throw.
            koin.completeSetup(1)
            startLoop(koin)

            advanceMillis(MILLIS_PER_MINUTE)

            assertEquals(1, refreshCycleAttempts.get(), "the refresh-cycle step was not attempted")
            assertEquals(1, updateCheckAttempts.get(), "the update-check step was not attempted")
            assertNotNull(koin.ftsRebuiltAt())
        } finally {
            driver.close()
        }
    }
}
