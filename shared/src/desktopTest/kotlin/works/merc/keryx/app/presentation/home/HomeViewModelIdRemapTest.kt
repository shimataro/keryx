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
import kotlin.test.assertNotEquals

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
            // The fixtures share one driver: stop every one of them before closing it, once.
            try {
                fixtures.forEach { it.stop() }
            } finally {
                driver.close()
            }
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

    // ---- The selection's identity across a re-key (generation) ----

    /**
     * Seeds three articles in f1 (display order s3, s2, s1), selects [selected], reports a viewport on
     * s2 only, and lets an article newer than the viewport land: one unseen article beyond it, which
     * the "new articles" pill counts.
     */
    private fun TestScope.selectWithOneUnseenArticle(vm: HomeViewModel, selected: ArticleFilter) {
        backgroundScope.launch { vm.newArticleCount.collect {} }
        vm.selectFilter(selected)
        testScheduler.advanceUntilIdle()
        vm.markArticlesSeen(listOf("s2"))
        testScheduler.advanceUntilIdle()
        db.insertArticle("fresh", "f1", publishedAt = 4000L)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.newArticleCount.value, "precondition: one unseen article beyond the viewport")
    }

    private fun KeryxDatabase.insertArticle(id: String, feedId: String, publishedAt: Long) {
        articlesQueries.insert(
            id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = "Title $id",
            summary = null, content = null, author = null, published_at = publishedAt,
            thumbnail_url = null, is_read = 0L, read_at = null, is_starred = 0L, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 0L, created_at = 0L,
        )
    }

    private fun seedArticles() {
        db.insertArticle("s1", "f1", publishedAt = 1000L)
        db.insertArticle("s2", "f1", publishedAt = 2000L)
        db.insertArticle("s3", "f1", publishedAt = 3000L)
    }

    @Test
    fun aReKeyOfTheSelectedTagKeepsTheGeneration() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Tag("t-old"))
        val before = vm.filterSelection.value

        driver.rekeyTagForTest("t-old", "t-new")
        testScheduler.advanceUntilIdle()
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()

        assertEquals(FilterSelection(ArticleFilter.Tag("t-new"), before.generation), vm.filterSelection.value)
        assertEquals(ArticleFilter.Tag("t-new"), vm.filter.value)
    }

    /**
     * KNOWN PRODUCTION GAP (reported, not fixed here): the merge's commit re-runs the old id's list
     * query, which is empty once the row has moved, BEFORE [TagFolderIdRemaps] publishes the move and
     * the selection follows. That emission drops every unseen id ([withList] narrows them to the
     * emitted ids), so the pill's count is already 0 when the filter switches to the new id.
     */
    @Test
    fun aReKeyOfTheSelectedTagKeepsThePillsUnseenCount() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Tag("t-old"))

        driver.rekeyTagForTest("t-old", "t-new")
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.newArticleCount.value)
    }

    @Test
    fun aReKeyOfTheSelectedFolderKeepsTheGeneration() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Folder("d-old"))
        val before = vm.filterSelection.value

        driver.rekeyFolderForTest("d-old", "d-new")
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()

        assertEquals(FilterSelection(ArticleFilter.Folder("d-new"), before.generation), vm.filterSelection.value)
    }

    @Test
    fun aReKeyOfTheSelectedFolderKeepsThePillsUnseenCount() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Folder("d-old"))

        driver.rekeyFolderForTest("d-old", "d-new")
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.newArticleCount.value)
    }

    /** The contrast: a switch the user makes does bump the generation and re-seed the tracking. */
    @Test
    fun aUserSwitchBumpsTheGenerationAndResetsTheTracking() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Tag("t-old"))
        val before = vm.filterSelection.value

        vm.selectFilter(ArticleFilter.All)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.All, vm.filterSelection.value.filter)
        assertNotEquals(before.generation, vm.filterSelection.value.generation)
        assertEquals(0, vm.newArticleCount.value, "the new list's first emission is the new baseline")
    }

    // ---- Actions made after a re-key is published but before Home's collector applies it ----
    //
    // The collector runs on the main dispatcher, so it can lag `idRemaps.current`; each test below
    // forces that interval: re-key, publish, then the action, and only then advanceUntilIdle().

    private suspend fun TestScope.rekeyAndPublishWithoutApplying(tag: Boolean = true, folder: Boolean = true) {
        if (tag) driver.rekeyTagForTest("t-old", "t-new")
        if (folder) driver.rekeyFolderForTest("d-old", "d-new")
        remaps.publishFromDb()
    }

    @Test
    fun reSelectingTheSameFilterBeforeTheRemapIsAppliedKeepsTheArticleAndGeneration() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()
        vm.selectArticle(vm.articles.value.first { it.id == "s2" })
        testScheduler.advanceUntilIdle()
        val before = vm.filterSelection.value.generation

        rekeyAndPublishWithoutApplying(folder = false)
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()

        assertEquals(FilterSelection(ArticleFilter.Tag("t-new"), before), vm.filterSelection.value)
        assertEquals("s2", vm.selectedArticle.value?.id)
    }

    @Test
    fun selectingAnotherFilterBeforeTheRemapIsAppliedStillSwitches() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()
        val before = vm.filterSelection.value.generation

        rekeyAndPublishWithoutApplying()
        vm.selectFilter(ArticleFilter.Folder("d-old"))
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Folder("d-new"), vm.filter.value)
        assertNotEquals(before, vm.filterSelection.value.generation)
    }

    /** A stale row's toggle before the remap is applied flips the state the row it became has. */
    @Test
    fun aStaleRowToggledBeforeTheRemapIsAppliedFlipsTheRowItBecame() = runVmTest {
        val vm = newViewModel().vm
        vm.toggleFolderCollapsed("d-old") // collapsed
        vm.toggleTagExpanded("t-old") // expanded
        testScheduler.advanceUntilIdle()

        rekeyAndPublishWithoutApplying()
        vm.toggleFolderCollapsed("d-old") // expands it again
        vm.toggleTagExpanded("t-old") // collapses it again
        testScheduler.advanceUntilIdle()

        assertEquals(emptySet(), vm.collapsedFolderIds.value)
        assertEquals(emptySet(), vm.expandedTagIds.value)
    }

    /** Revealing a row by its stale id before the remap is applied expands what hides the row it became. */
    @Test
    fun revealingAStaleRowBeforeTheRemapIsAppliedExpandsTheRowItBecame() = runVmTest {
        val vm = newViewModel().vm
        vm.toggleFolderCollapsed("d-old")
        testScheduler.advanceUntilIdle()

        rekeyAndPublishWithoutApplying()
        vm.revealFeedListRow(FeedListRowSelection.FeedInFolderGroup("f1"))
        vm.revealFeedListRow(FeedListRowSelection.FeedInTag("f1", "t-old"))
        testScheduler.advanceUntilIdle()

        assertEquals(emptySet(), vm.collapsedFolderIds.value)
        assertEquals(setOf("t-new"), vm.expandedTagIds.value)
    }

    /** Selecting the row the selection already is (by its stale or new id) changes nothing, generation included. */
    @Test
    fun reSelectingTheReKeyedRowKeepsTheGeneration() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()
        val before = vm.filterSelection.value.generation
        driver.rekeyTagForTest("t-old", "t-new")
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()
        assertEquals(before, vm.filterSelection.value.generation)

        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.selectFilter(ArticleFilter.Tag("t-new"))

        assertEquals(before, vm.filterSelection.value.generation)
    }
}
