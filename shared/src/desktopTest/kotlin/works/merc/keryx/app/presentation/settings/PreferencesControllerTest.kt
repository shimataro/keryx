package works.merc.keryx.app.presentation.settings

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import works.merc.keryx.app.core.LOCAL_WRITE_REREAD_RETRY_INITIAL_MS
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.core.SETTING_CACHE_RETENTION_DAYS
import works.merc.keryx.app.core.SETTING_READ_TIMEOUT_SECONDS
import works.merc.keryx.app.insertGlobalSetting
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FEED_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferencesControllerTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "preferences-controller-test-${Random.nextInt()}")

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        File(dir).deleteRecursively()
    }

    private fun newController(
        database: KeryxDatabase = db,
        writeDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): PreferencesController {
        // Unconfined write dispatcher so every write persists inline, keeping store.load() assertions
        // deterministic without a test coroutine scheduler.
        val settingsRepository = SettingsRepository(
            database, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L },
            writeDispatcher = writeDispatcher,
        )
        return PreferencesController(settingsRepository)
    }

    /** How many of the next `global_settings` writes through [failingDb] fail. */
    private var globalWritesToFail = 0

    /** How many of the next `global_settings` reads through [failingDb] fail. */
    private var globalReadsToFail = 0
    private fun failingDb() = KeryxDatabase(object : SqlDriver by driver {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (app.cash.sqldelight.db.SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (globalReadsToFail > 0 && sql.contains("global_settings")) {
                globalReadsToFail--
                throw IllegalStateException("database is locked")
            }
            return driver.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (globalWritesToFail > 0 && sql.contains("global_settings")) {
                globalWritesToFail--
                throw IllegalStateException("database is locked")
            }
            return driver.execute(identifier, sql, parameters, binders)
        }
    })

    @Test
    fun aFailedReadTimeoutWriteShowsTheStoredValueAgain() {
        db.insertGlobalSetting(SETTING_READ_TIMEOUT_SECONDS, "45")
        val controller = newController(failingDb())
        globalWritesToFail = 1

        controller.updateReadTimeout(90)

        assertEquals(45, controller.readTimeoutSeconds.value)
    }

    @Test
    fun aFailedCacheRetentionWriteShowsTheStoredValueAgain() {
        db.insertGlobalSetting(SETTING_CACHE_RETENTION_DAYS, "7")
        val controller = newController(failingDb())
        globalWritesToFail = 1

        controller.updateCacheRetention(null)

        assertEquals(7, controller.cacheRetentionDays.value)
    }

    @Test
    fun aFailedWriteWhoseReReadFailsShowsTheStoredValueOnceItCanBeRead() {
        db.insertGlobalSetting(SETTING_READ_TIMEOUT_SECONDS, "45")
        val scheduler = TestCoroutineScheduler()
        val controller = newController(failingDb(), StandardTestDispatcher(scheduler))
        globalWritesToFail = 1
        // The read right after the failed write, and the first retry, fail too.
        globalReadsToFail = 2

        controller.updateReadTimeout(90)
        scheduler.runCurrent()
        assertEquals(90, controller.readTimeoutSeconds.value)

        scheduler.advanceTimeBy(LOCAL_WRITE_REREAD_RETRY_INITIAL_MS)
        scheduler.runCurrent()
        assertEquals(90, controller.readTimeoutSeconds.value)

        // The wait doubles; the next attempt reads the stored value.
        scheduler.advanceTimeBy(2 * LOCAL_WRITE_REREAD_RETRY_INITIAL_MS)
        scheduler.runCurrent()
        assertEquals(45, controller.readTimeoutSeconds.value)
        assertEquals(0, globalReadsToFail)
    }

    @Test
    fun aPendingReReadStopsOnceTheSameSettingIsChangedAgain() {
        db.insertGlobalSetting(SETTING_READ_TIMEOUT_SECONDS, "45")
        val scheduler = TestCoroutineScheduler()
        val controller = newController(failingDb(), StandardTestDispatcher(scheduler))
        globalWritesToFail = 1
        globalReadsToFail = 1

        controller.updateReadTimeout(90)
        scheduler.runCurrent()
        controller.updateReadTimeout(60)
        scheduler.advanceUntilIdle()

        assertEquals(60, controller.readTimeoutSeconds.value)
        assertEquals(60, newController().readTimeoutSeconds.value)
    }

    @Test
    fun aFailedWriteDoesNotOverwriteANewerChangeOfTheSameSetting() {
        val writes = HoldingDispatcher()
        val controller = newController(failingDb(), writes)
        writes.hold()

        controller.updateReadTimeout(60)
        controller.updateReadTimeout(90)
        // The first write fails, the second one lands.
        globalWritesToFail = 1
        writes.release()

        assertEquals(90, controller.readTimeoutSeconds.value)
        assertEquals(90, newController().readTimeoutSeconds.value)
    }

    @Test
    fun themeModeSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setThemeMode("dark")

        assertEquals("dark", controller.localSettings.value.themeMode)
    }

    @Test
    fun fontScaleSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setFontScale(1.5)

        assertEquals(1.5, controller.localSettings.value.fontSizeScale)
    }

    @Test
    fun syncOnMeteredNetworkSetterPersistsToLocalSettings() {
        val controller = newController()
        assertFalse(controller.localSettings.value.syncOnMeteredNetwork)

        controller.setSyncOnMeteredNetwork(true)

        assertTrue(controller.localSettings.value.syncOnMeteredNetwork)
        assertTrue(LocalSettingsStore(dirOverride = dir).load().syncOnMeteredNetwork)
    }

    @Test
    fun refreshIntervalSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setRefreshIntervalMinutes(15)

        assertEquals(15, controller.localSettings.value.refreshIntervalMinutes)
    }

    @Test
    fun notificationEnabledSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setNotificationEnabled(false)

        assertFalse(controller.localSettings.value.notificationEnabled)
    }

    @Test
    fun startMinimizedSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setStartMinimized(true)

        assertTrue(controller.localSettings.value.startMinimized)
    }

    @Test
    fun updateCheckIntervalHoursSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setUpdateCheckIntervalHours(72)

        assertEquals(72, controller.localSettings.value.updateCheckIntervalHours)
    }

    @Test
    fun localSettingsRoundTripsThroughStore() {
        val store = LocalSettingsStore(dirOverride = dir)
        val controller = newController()

        controller.setThemeMode("dark")

        assertEquals("dark", store.load().themeMode)
    }

    @Test
    fun updateReadTimeoutUpdatesStateAndRepository() {
        val controller = newController()

        controller.updateReadTimeout(60)

        assertEquals(60, controller.readTimeoutSeconds.value)
        assertEquals(60, db.global_settingsQueries.get("read_timeout_seconds").executeAsOne().toInt())
    }

    @Test
    fun updateCacheRetentionUpdatesStateAndRepositoryWithNullSentinel() {
        val controller = newController()

        controller.updateCacheRetention(null)

        assertNull(controller.cacheRetentionDays.value)
        assertEquals("null", db.global_settingsQueries.get("cache_retention_days").executeAsOne())
    }

    @Test
    fun updateCacheRetentionUpdatesStateAndRepositoryWithExplicitValue() {
        val controller = newController()

        controller.updateCacheRetention(7)

        assertEquals(7, controller.cacheRetentionDays.value)
        assertEquals(7, db.global_settingsQueries.get("cache_retention_days").executeAsOne().toInt())
    }

    @Test
    fun feedListPaneWidthSetterPersistsAndClampsToTheSharedRange() {
        val controller = newController()

        controller.setFeedListPaneWidth(300.0)
        assertEquals(300.0, controller.localSettings.value.feedListPaneWidth)

        controller.setFeedListPaneWidth(10.0)
        assertEquals(FEED_LIST_PANE_MIN_WIDTH.toDouble(), controller.localSettings.value.feedListPaneWidth)

        controller.setFeedListPaneWidth(10_000.0)
        assertEquals(FEED_LIST_PANE_MAX_WIDTH.toDouble(), controller.localSettings.value.feedListPaneWidth)
    }

    @Test
    fun articleListPaneWidthSetterPersistsAndClampsToTheSharedRange() {
        val controller = newController()

        controller.setArticleListPaneWidth(400.0)
        assertEquals(400.0, controller.localSettings.value.articleListPaneWidth)

        controller.setArticleListPaneWidth(10.0)
        assertEquals(ARTICLE_LIST_PANE_MIN_WIDTH.toDouble(), controller.localSettings.value.articleListPaneWidth)

        controller.setArticleListPaneWidth(10_000.0)
        assertEquals(ARTICLE_LIST_PANE_MAX_WIDTH.toDouble(), controller.localSettings.value.articleListPaneWidth)
    }

    @Test
    fun lastFocusedPaneSetterPersistsToLocalSettings() {
        val controller = newController()

        controller.setLastFocusedPane("ArticleDetail")

        assertEquals("ArticleDetail", controller.localSettings.value.lastFocusedPane)
    }
}
