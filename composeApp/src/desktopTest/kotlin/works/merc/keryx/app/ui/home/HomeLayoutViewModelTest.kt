package works.merc.keryx.app.ui.home

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLayoutViewModelTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "home-layout-vm-test-${Random.nextInt()}")
    private val created = mutableListOf<HomeLayoutViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private fun store() = LocalSettingsStore(dirOverride = dir)

    private fun newViewModel(): HomeLayoutViewModel =
        HomeLayoutViewModel(
            SettingsRepository(db, store(), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined),
        ).also { created += it }

    @Test
    fun setFeedListPaneWidthAndSetArticleListPaneWidthPersistAfterDebounce() = runTest {
        val vm = newViewModel()

        vm.setFeedListPaneWidth(300.0)
        vm.setArticleListPaneWidth(400.0)
        testScheduler.advanceUntilIdle()

        val settings = store().load()
        assertEquals(300.0, settings.feedListPaneWidth)
        assertEquals(400.0, settings.articleListPaneWidth)
    }

    @Test
    fun setFocusedPanePersistsToLocalSettings() = runTest {
        val vm = newViewModel()
        assertNull(store().load().lastFocusedPane)

        vm.setFocusedPane(HomePane.FeedList)

        assertEquals("FeedList", store().load().lastFocusedPane)
    }

    @Test
    fun getInitialFocusedPaneDefaultsToArticleListWhenNothingSaved() = runTest {
        assertEquals(HomePane.ArticleList, newViewModel().getInitialFocusedPane())
    }

    @Test
    fun restartRestoresFocusedPane() = runTest {
        newViewModel().setFocusedPane(HomePane.FeedList)

        assertEquals(HomePane.FeedList, newViewModel().getInitialFocusedPane())
    }

    @Test
    fun getInitialFocusedPaneFallsBackToArticleListWhenPersistedValueIsInvalid() = runTest {
        store().save(store().load().copy(lastFocusedPane = "bogus"))

        assertEquals(HomePane.ArticleList, newViewModel().getInitialFocusedPane())
    }
}
