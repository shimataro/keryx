package works.merc.keryx.app.presentation.home

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.LocalWriteFailureReporter
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.TagFolderIdRemaps
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.rekeyFolderForTest
import works.merc.keryx.app.rekeyTagForTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A merge that re-keys a tag or folder onto another id publishes the move ([TagFolderIdRemaps]), and
 * [HomeViewModel] brings every id it holds up to date, so nothing the user had selected or expanded
 * is lost (external-spec §9, "Least surprise"): the selection follows the row instead of falling
 * back to All Feeds. The re-key is simulated as the merge will do it ([rekeyTagForTest] /
 * [rekeyFolderForTest]).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelIdRemapTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private lateinit var remaps: TagFolderIdRemaps
    private val notifications = NotificationCenter()
    private val fixtures = mutableListOf<HomeViewModelFixture>()

    @BeforeTest
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val (d, database) = inMemoryDb()
        driver = d
        db = database
        db.insertFolder("d-old", "Work")
        db.insertTag("t-old", "News")
        db.insertFeed("f1", folderId = "d-old")
        db.insertFeedTag("f1", "t-old")
        remaps = TagFolderIdRemaps(db)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit): TestResult = runTest(scheduler) {
        try {
            body()
        } finally {
            fixtures.forEach { it.close() }
        }
    }

    private fun TestScope.newViewModel(): HomeViewModelFixture {
        val fixture = newHomeViewModel(
            driver, db,
            clock = Clock { 100L },
            localWriteFailureReporter = LocalWriteFailureReporter(notifications, Clock { 100L }),
            idRemaps = remaps,
        )
        fixtures += fixture
        backgroundScope.launch { fixture.vm.articles.collect {} }
        testScheduler.advanceUntilIdle()
        return fixture
    }

    private suspend fun TestScope.rekeyAndPublish() {
        driver.rekeyTagForTest("t-old", "t-new")
        driver.rekeyFolderForTest("d-old", "d-new")
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aSelectedTagFollowsItsReKeyInsteadOfFallingBack() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.toggleTagExpanded("t-old")
        testScheduler.advanceUntilIdle()

        rekeyAndPublish()

        assertEquals(ArticleFilter.Tag("t-new"), vm.filter.value)
        assertEquals(FeedListRowSelection.Tag("t-new"), vm.selectedRowInstance.value)
        assertEquals(setOf("t-new"), vm.expandedTagIds.value)
    }

    @Test
    fun aSelectedFolderAndItsCollapsedStateFollowTheirReKey() = runVmTest {
        val fixture = newViewModel()
        val vm = fixture.vm
        vm.selectFilter(ArticleFilter.Folder("d-old"))
        vm.toggleFolderCollapsed("d-old")
        testScheduler.advanceUntilIdle()

        rekeyAndPublish()

        assertEquals(ArticleFilter.Folder("d-new"), vm.filter.value)
        assertEquals(FeedListRowSelection.Folder("d-new"), vm.selectedRowInstance.value)
        assertEquals(setOf("d-new"), vm.collapsedFolderIds.value)
    }

    /** A feed row nested under a re-keyed tag stays selected, under the tag's new id. */
    @Test
    fun aFeedRowNestedUnderAReKeyedTagStaysSelected() = runVmTest {
        val vm = newViewModel().vm
        vm.toggleTagExpanded("t-old")
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t-old"))
        testScheduler.advanceUntilIdle()

        rekeyAndPublish()

        assertEquals(ArticleFilter.Feed("f1"), vm.filter.value)
        assertEquals(FeedListRowSelection.FeedInTag("f1", "t-new"), vm.selectedRowInstance.value)
    }

    /**
     * A row the feed list rendered before the re-key still carries the old id; selecting or toggling
     * it acts on the row it became.
     */
    @Test
    fun aStaleRowSelectedOrToggledAfterTheReKeyActsOnTheRowItBecame() = runVmTest {
        val vm = newViewModel().vm
        rekeyAndPublish()

        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.toggleFolderCollapsed("d-old")
        vm.toggleTagExpanded("t-old")
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Tag("t-new"), vm.filter.value)
        assertEquals(FeedListRowSelection.Tag("t-new"), vm.selectedRowInstance.value)
        assertEquals(setOf("d-new"), vm.collapsedFolderIds.value)
        assertEquals(setOf("t-new"), vm.expandedTagIds.value)
    }

    /** A ViewModel created after the re-key was published starts from the current map, missing nothing. */
    @Test
    fun aViewModelCreatedAfterThePublishStillResolvesStaleIds() = runVmTest {
        val first = newViewModel().vm
        first.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()
        rekeyAndPublish()

        val restored = newHomeViewModel(
            driver, db,
            clock = Clock { 100L },
            idRemaps = remaps,
        ).also { fixtures += it }.vm
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Tag("t-new"), first.filter.value)
        // A separate fixture has its own settings directory, so only the shared map can carry this.
        restored.selectFilter(ArticleFilter.Tag("t-old"))
        assertEquals(ArticleFilter.Tag("t-new"), restored.filter.value)
    }

    /** With nothing re-keyed, a publish changes nothing. */
    @Test
    fun aPublishWithNoReKeyLeavesTheSelectionAlone() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.toggleFolderCollapsed("d-old")
        testScheduler.advanceUntilIdle()

        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Tag("t-old"), vm.filter.value)
        assertEquals(setOf("d-old"), vm.collapsedFolderIds.value)
    }
}
