package works.merc.keryx.app.presentation.settings

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
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

    private fun newController(): PreferencesController {
        // Unconfined write dispatcher so every write persists inline, keeping store.load() assertions
        // deterministic without a test coroutine scheduler.
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L },
            writeDispatcher = Dispatchers.Unconfined,
        )
        return PreferencesController(settingsRepository)
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
