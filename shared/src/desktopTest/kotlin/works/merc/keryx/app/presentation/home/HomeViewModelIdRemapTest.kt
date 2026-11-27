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
import works.merc.keryx.app.domain.FeedIdentitySql
import works.merc.keryx.app.domain.IdRemaps
import works.merc.keryx.app.HoldingDispatcher
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
 * A merge that re-keys a tag or folder onto another id publishes the move ([IdRemaps]), and
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
    private lateinit var remaps: IdRemaps
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
        remaps = IdRemaps(db, Dispatchers.Unconfined)
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

    private fun TestScope.newViewModel(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ): HomeViewModelFixture {
        val fixture = newHomeViewModel(
            driver, db,
            dispatcher = dispatcher,
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
     * The merge's commit re-runs the old id's list query BEFORE [IdRemaps] publishes the move
     * and the selection follows — the interval this test forces. That query resolves the old id through
     * `ks_id_remap` (`articles.sq`'s `watchByTag` / `watchByFolder`), so it still returns the same rows
     * rather than an empty list that would drop every unseen id ([withList] narrows them to the
     * emitted ids): the pill keeps its count across the re-key.
     */
    @Test
    fun aReKeyOfTheSelectedTagKeepsThePillsUnseenCount() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Tag("t-old"))

        driver.rekeyTagForTest("t-old", "t-new")
        // The interval between the merge's commit and the publish: the old id's list query re-runs.
        testScheduler.advanceUntilIdle()
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

    /** As [aReKeyOfTheSelectedTagKeepsThePillsUnseenCount], for a folder. */
    @Test
    fun aReKeyOfTheSelectedFolderKeepsThePillsUnseenCount() = runVmTest {
        seedArticles()
        val vm = newViewModel().vm
        selectWithOneUnseenArticle(vm, ArticleFilter.Folder("d-old"))

        driver.rekeyFolderForTest("d-old", "d-new")
        // The interval between the merge's commit and the publish: the old id's list query re-runs.
        testScheduler.advanceUntilIdle()
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

    /**
     * A delete lands, then a merge re-keys the deleted tag and publishes the move before Home applies
     * it: the delete's fallback still takes the selection to All (not onto the deleted row's new id)
     * and forgets the tag's expansion.
     */
    @Test
    fun aDeletedTagsFallbackAfterAPublishedRemapStillFallsBack() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.toggleTagExpanded("t-old")
        testScheduler.advanceUntilIdle()

        vm.deleteTag("t-old")
        rekeyAndPublishWithoutApplying(folder = false)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.All, vm.filter.value)
        assertEquals(FeedListRowSelection.canonicalFor(ArticleFilter.All), vm.selectedRowInstance.value)
        assertEquals(emptySet(), vm.expandedTagIds.value)
    }

    /** As [aDeletedTagsFallbackAfterAPublishedRemapStillFallsBack], for a folder. */
    @Test
    fun aDeletedFoldersFallbackAfterAPublishedRemapStillFallsBack() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Folder("d-old"))
        vm.toggleFolderCollapsed("d-old")
        testScheduler.advanceUntilIdle()

        vm.deleteFolder("d-old")
        rekeyAndPublishWithoutApplying(tag = false)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.All, vm.filter.value)
        assertEquals(emptySet(), vm.collapsedFolderIds.value)
    }

    /**
     * The re-key is committed and the lists have re-run over it (another write notified them), but the map
     * is not yet published: [HomeViewModel.catchUpIdRemapFromDb] reads it from the database, publishes it
     * under the map's lock and applies it, so a UI deciding that a row it holds is gone first sees the row
     * under its new id. The selection and expansion follow the re-key instead of being lost.
     */
    @Test
    fun catchingUpFromTheDatabaseBringsAListAheadOfTheMapIntoStep() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.toggleTagExpanded("t-old")
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()

        driver.rekeyTagForTest("t-old", "t-new")
        driver.rekeyFolderForTest("d-old", "d-new")
        // Another writer's notification re-runs the lists over the committed rows; the map stays unpublished.
        db.insertFeed("f2")
        testScheduler.advanceUntilIdle()
        assertEquals(emptyMap(), vm.idRemap.value.tagIds, "precondition: the map is still stale")
        assertEquals(listOf("t-new"), vm.tags.value.map { it.id }, "precondition: the list already has the new id")

        assertEquals(true, vm.catchUpIdRemapFromDb())

        assertEquals(mapOf("t-old" to "t-new"), vm.idRemap.value.tagIds)
        assertEquals(mapOf("d-old" to "d-new"), vm.idRemap.value.folderIds)
        assertEquals(ArticleFilter.Tag("t-new"), vm.filter.value)
        assertEquals(setOf("t-new"), vm.expandedTagIds.value)
    }

    /** With nothing re-keyed, the catch-up changes nothing: an absence it cannot explain stays a real one. */
    @Test
    fun catchingUpWithNothingReKeyedChangesNothing() = runVmTest {
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.catchUpIdRemapFromDb())

        assertEquals(ArticleFilter.Tag("t-old"), vm.filter.value)
        assertEquals(emptyMap(), vm.idRemap.value.tagIds)
    }

    private fun unreadIds(): List<String> =
        db.articlesQueries.watchAll().executeAsList().filter { it.is_read == 0L }.map { it.id }.sorted()

    /**
     * Mark All as Read made right after selecting a tag waits for the tag's list; a merge re-keys the
     * tag meanwhile and publishes the move before Home applies it. The selection following the
     * re-key is not a switch, so the articles are still marked read.
     */
    @Test
    fun markAllReadWaitingAcrossAReKeyOfItsTagStillMarks() = runVmTest {
        seedArticles()
        // The list's flows on the test scheduler, so Mark All as Read waits for the tag's list.
        val vm = newViewModel(dispatcher = StandardTestDispatcher(testScheduler)).vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.markAllRead()
        rekeyAndPublishWithoutApplying(folder = false)
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Tag("t-new"), vm.filter.value)
        assertEquals(emptyList(), unreadIds())
    }

    /** The contrast: a switch the user makes while it waits still cancels it, re-key or not. */
    @Test
    fun markAllReadWaitingAcrossAReKeyIsStillCancelledByAUserSwitch() = runVmTest {
        seedArticles()
        // The list's flows on the test scheduler, so Mark All as Read waits for the tag's list.
        val vm = newViewModel(dispatcher = StandardTestDispatcher(testScheduler)).vm
        vm.selectFilter(ArticleFilter.Tag("t-old"))
        vm.markAllRead()
        rekeyAndPublishWithoutApplying()
        vm.selectFilter(ArticleFilter.Folder("d-old"))
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Folder("d-new"), vm.filter.value)
        assertEquals(listOf("s1", "s2", "s3"), unreadIds())
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

    // ---- Feed re-keys: a merge moving a feed onto its identity's canonical id ----

    /**
     * Merges f1 into f0 (the smaller id, so f0 is canonical) as a redirect merge does
     * ([FeedIdentitySql.mergeLocally] in a transaction), publishes the re-key unless [publish] is false,
     * then re-runs the lists as the merge's commit does.
     */
    private suspend fun TestScope.mergeF1IntoF0(publish: Boolean = true) {
        db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") }
        if (publish) remaps.publishFromDb()
        driver.notifyListeners("feeds", "articles", "feed_tags", "feed_fetch_state")
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aSelectedFeedAndItsRowFollowAMergeKeepingTheGeneration() = runVmTest {
        db.insertFeed("f0")
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()
        val generation = vm.filterSelection.value.generation

        mergeF1IntoF0()

        assertEquals(ArticleFilter.Feed("f0"), vm.filter.value)
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f0"), vm.selectedRowInstance.value)
        assertEquals(generation, vm.filterSelection.value.generation, "the same list under a new id is not a switch")
    }

    @Test
    fun aFeedRowNestedUnderATagFollowsAMerge() = runVmTest {
        db.insertFeed("f0")
        val vm = newViewModel().vm
        vm.toggleTagExpanded("t-old")
        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t-old"))
        testScheduler.advanceUntilIdle()

        mergeF1IntoF0()

        assertEquals(FeedListRowSelection.FeedInTag("f0", "t-old"), vm.selectedRowInstance.value)
    }

    /** A feed row rendered before the merge still carries the old id; selecting it selects the feed it became. */
    @Test
    fun aStaleFeedRowSelectedAfterTheMergeSelectsTheFeedItBecame() = runVmTest {
        db.insertFeed("f0")
        val vm = newViewModel().vm
        mergeF1IntoF0()

        vm.selectFilter(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInFolderGroup("f1"))

        assertEquals(ArticleFilter.Feed("f0"), vm.filter.value)
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f0"), vm.selectedRowInstance.value)
    }

    /**
     * The merge has committed and the feed list already shows only the merged feed, but the map is not
     * published yet (the redirect merge's generated statements notify at its commit, before it
     * publishes): the catch-up from the database moves the selection instead of losing it.
     */
    @Test
    fun catchingUpFromTheDatabaseMovesTheSelectedFeed() = runVmTest {
        db.insertFeed("f0")
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()

        mergeF1IntoF0(publish = false)
        assertEquals(listOf("f0"), vm.feeds.value.map { it.id }, "precondition: the list already has only the merged feed")
        assertEquals(ArticleFilter.Feed("f1"), vm.filter.value, "precondition: the map is still stale")

        assertEquals(true, vm.catchUpIdRemapFromDb())

        assertEquals(mapOf("f1" to "f0"), vm.idRemap.value.feedIds)
        assertEquals(ArticleFilter.Feed("f0"), vm.filter.value)
    }

    /**
     * An unsubscribe lands, then a merge folds the unsubscribed feed into another one, and the merged feed
     * comes out unsubscribed (the unsubscribe's stamp is the newest): the selection, which followed the
     * feed onto the merged id, still falls back to All Feeds — the fallback compares it in the current
     * map's id space, not with the id the unsubscribe was given.
     */
    @Test
    fun anUnsubscribedFeedsFallbackAcrossAMergeStillFallsBack() = runVmTest {
        db.insertFeed("f0")
        val vm = newViewModel().vm
        vm.selectFilter(ArticleFilter.Feed("f1"))
        testScheduler.advanceUntilIdle()

        vm.unsubscribeFeed("f1")
        mergeF1IntoF0()
        assertEquals(100L, db.feedsQueries.getById("f0").executeAsOne().deleted_at, "precondition: the merged feed is unsubscribed")

        assertEquals(ArticleFilter.All, vm.filter.value)
    }

    /**
     * A saved feed filter whose feed a merge moved while the app was closed is restored under the merged
     * feed's id — read from the database, so even before the published map has loaded — instead of
     * falling back to All Feeds because the saved id's row is gone.
     */
    @Test
    fun aSavedFeedFilterIsRestoredUnderTheMergedFeedsIdBeforeTheMapLoads() = runVmTest {
        db.insertFeed("f0")
        db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") }
        val dir = works.merc.keryx.app.platform.FileIO.join(works.merc.keryx.app.platform.AppDirs.tempDir(), "home-vm-remap-${kotlin.random.Random.nextInt()}")
        works.merc.keryx.app.data.local.LocalSettingsStore(dirOverride = dir)
            .save(works.merc.keryx.app.data.local.LocalSettings(lastFilter = "feed:f1"))
        // A map whose load never runs: the restore must not depend on it.
        val neverLoaded = IdRemaps({ error("not loaded") }, StandardTestDispatcher(TestCoroutineScheduler()))

        val vm = newHomeViewModel(driver, db, clock = Clock { 100L }, idRemaps = neverLoaded, settingsDir = dir)
            .also { fixtures += it }.vm

        assertEquals(ArticleFilter.Feed("f0"), vm.filter.value)
        assertEquals(true, vm.filterRestoredOnLaunch)
    }

    /**
     * A merge that commits between the two reads a saved feed filter's validation used to make (resolve
     * the id, then read the row) left the resolved id naming a row the merge had just deleted, and the
     * restore fell back to All although the feed is still subscribed. Resolved and read in one statement,
     * there is no gap for the merge to land in.
     */
    @Test
    fun aSavedFeedFilterSurvivesAMergeCommittingWhileItsTargetIsValidated() = runVmTest {
        db.insertFeed("f0")
        val hooked = AfterFeedIdResolveDriver(driver)
        // The merge lands right after the saved id has been resolved, before anything reads its row.
        hooked.afterResolve = { db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") } }
        val dir = works.merc.keryx.app.platform.FileIO.join(works.merc.keryx.app.platform.AppDirs.tempDir(), "home-vm-remap-${kotlin.random.Random.nextInt()}")
        works.merc.keryx.app.data.local.LocalSettingsStore(dirOverride = dir)
            .save(works.merc.keryx.app.data.local.LocalSettings(lastFilter = "feed:f1"))
        val neverLoaded = IdRemaps({ error("not loaded") }, StandardTestDispatcher(TestCoroutineScheduler()))

        val vm = newHomeViewModel(hooked, KeryxDatabase(hooked), clock = Clock { 100L }, idRemaps = neverLoaded, settingsDir = dir)
            .also { fixtures += it }.vm

        assertNotEquals(ArticleFilter.All, vm.filter.value)
        assertEquals(true, vm.filterRestoredOnLaunch)
    }

    /** A saved feed filter whose feed is gone, or unsubscribed, still falls back to All Feeds. */
    @Test
    fun aSavedFeedFilterOfAnUnknownOrUnsubscribedFeedFallsBackToAll() = runVmTest {
        db.insertFeed("f-gone", deletedAt = 50L)
        for (saved in listOf("feed:f-gone", "feed:f-unknown")) {
            val dir = works.merc.keryx.app.platform.FileIO.join(works.merc.keryx.app.platform.AppDirs.tempDir(), "home-vm-remap-${kotlin.random.Random.nextInt()}")
            works.merc.keryx.app.data.local.LocalSettingsStore(dirOverride = dir)
                .save(works.merc.keryx.app.data.local.LocalSettings(lastFilter = saved))
            val neverLoaded = IdRemaps({ error("not loaded") }, StandardTestDispatcher(TestCoroutineScheduler()))

            val vm = newHomeViewModel(driver, db, clock = Clock { 100L }, idRemaps = neverLoaded, settingsDir = dir)
                .also { fixtures += it }.vm

            assertEquals(ArticleFilter.All, vm.filter.value, saved)
        }
    }

    /**
     * A notification's "show feed" names the id the feed had when it was raised; a merge committed since
     * but not yet published moved it. The action catches the map up from the database first, so it
     * selects the feed it became rather than a feed that no longer exists.
     */
    @Test
    fun showFeedDetailSelectsTheFeedAMergeMovedTheNotificationsFeedTo() = runVmTest {
        db.insertFeed("f0")
        val vm = newViewModel().vm
        mergeF1IntoF0(publish = false)

        vm.showFeedDetail("f1")

        assertEquals(ArticleFilter.Feed("f0"), vm.filter.value)
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f0"), vm.selectedRowInstance.value)
    }

    /**
     * A feed the user selects while a "show feed" is still catching the map up is the newer request:
     * the notification's action then selects nothing, rather than taking the list back to its feed.
     */
    @Test
    fun showFeedDetailSelectsNothingOnceANewerSelectionWasMadeDuringItsCatchUp() = runVmTest {
        db.insertFeed("f2")
        val held = HoldingDispatcher()
        val vm = newViewModel(dispatcher = held).vm

        held.hold()
        val show = launch { vm.showFeedDetail("f1") }
        testScheduler.runCurrent()
        assertEquals(true, show.isActive, "precondition: the catch-up is held")
        vm.selectFilter(ArticleFilter.Feed("f2"))
        held.release()
        show.join()
        testScheduler.advanceUntilIdle()

        assertEquals(ArticleFilter.Feed("f2"), vm.filter.value)
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f2"), vm.selectedRowInstance.value)
    }
}

/**
 * Passes every statement through to [delegate]; [afterResolve], when set, runs once, right after the next
 * `feeds.resolveId` statement has returned its id — the one point at which a test can put another
 * connection's commit between a feed id's resolution and the read of its row.
 */
private class AfterFeedIdResolveDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    @kotlin.concurrent.Volatile var afterResolve: (() -> Unit)? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): app.cash.sqldelight.db.QueryResult<R> {
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        if (sql.trimStart().startsWith("SELECT COALESCE((SELECT id FROM feeds WHERE id")) {
            afterResolve?.also { afterResolve = null }?.invoke()
        }
        return result
    }
}
