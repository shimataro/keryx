package works.merc.keryx.app.presentation.home

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.FeedIdentitySql
import works.merc.keryx.app.domain.IdRemaps
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A feed merge moves each article of the folded feeds onto the smallest id of its (feed, guid) key and
 * records the move in `ks_id_remap` (kind `article`), which is not published as a whole: [HomeViewModel]
 * resolves the article ids it holds from the database when one stops naming a row, so the selection,
 * its pins and the saved article follow the article instead of being taken for deleted.
 *
 * Fixture: feeds f0 and f1 both hold the article with guid "g" — a0 in f0, a1 in f1 — so merging f1 into
 * f0 moves a1 onto a0.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelArticleRekeyTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private lateinit var remaps: IdRemaps
    private val fixtures = mutableListOf<HomeViewModelFixture>()

    @BeforeTest
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val (raw, _) = inMemoryDb()
        driver = ArticleLookupHookDriver(raw)
        db = KeryxDatabase(driver)
        db.insertFeed("f0")
        db.insertFeed("f1")
        db.insertArticle("a0", "f0", guid = "g", publishedAt = 2000L)
        db.insertArticle("a1", "f1", guid = "g", publishedAt = 1000L)
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
            try {
                fixtures.forEach { it.stop() }
            } finally {
                driver.close()
            }
        }
    }

    private fun KeryxDatabase.insertArticle(id: String, feedId: String, guid: String, publishedAt: Long) {
        articlesQueries.insert(
            id = id, feed_id = feedId, guid = guid, url = "https://article/$id", title = "Title $id",
            summary = null, content = "Body $id", author = null, published_at = publishedAt,
            thumbnail_url = null, is_read = 0L, read_at = null, is_starred = 0L, starred_at = null,
            cached_at = 0L, search_text = "", updated_at = 0L, created_at = 0L,
        )
    }

    private fun TestScope.newViewModel(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        settingsDir: String? = null,
    ): HomeViewModel {
        val fixture = newHomeViewModel(
            driver, db,
            dispatcher = dispatcher,
            clock = Clock { 100L },
            idRemaps = remaps,
            settingsDir = settingsDir,
        )
        fixtures += fixture
        backgroundScope.launch { fixture.vm.articles.collect {} }
        testScheduler.advanceUntilIdle()
        return fixture.vm
    }

    /** Merges f1 into f0 as a redirect merge does: the commit notifies the lists, then the feed re-keys are published. */
    private suspend fun TestScope.mergeF1IntoF0() {
        db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") }
        driver.notifyListeners("feeds", "articles", "feed_tags", "feed_fetch_state")
        testScheduler.advanceUntilIdle()
        remaps.publishFromDb()
        testScheduler.advanceUntilIdle()
    }

    private fun HomeViewModel.row(id: String) = articles.value.single { it.id == id }

    @Test
    fun theSelectedArticleAndItsPinFollowAMergeWithoutScrolling() = runVmTest {
        val vm = newViewModel()
        vm.setUnreadOnly(true)
        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()
        assertEquals("a1", vm.selectedArticleId.value)
        val reveals = vm.selectionRevealRequests.value

        mergeF1IntoF0()

        assertEquals("a0", vm.selectedArticleId.value)
        assertEquals("a0", vm.selectedArticle.value?.id)
        // The pin moved with it, so the read article is still on the unread-only list, once.
        assertEquals(listOf("a0"), vm.articles.value.map { it.id })
        assertEquals(reveals, vm.selectionRevealRequests.value, "a move is not a selection: the list does not scroll")
    }

    /**
     * The selection's body lookup is held while the merge moves the article: the lookup, resolving the id,
     * finds the row it became and the selection lands on it, instead of being taken for deleted.
     */
    @Test
    fun aSelectionStillLoadingWhenAMergeMovesItLandsOnTheArticleItBecame() = runVmTest {
        val lookups = HoldingDispatcher()
        val vm = newViewModel(dispatcher = lookups)
        val row = vm.row("a1")
        lookups.hold()
        vm.selectArticle(row)
        testScheduler.advanceUntilIdle()

        db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") }
        lookups.release()
        testScheduler.advanceUntilIdle()
        driver.notifyListeners("feeds", "articles", "feed_tags", "feed_fetch_state")
        testScheduler.advanceUntilIdle()

        assertEquals("a0", vm.selectedArticleId.value)
        assertEquals("a0", vm.selectedArticle.value?.id)
    }

    /** The saved article, moved while the app was closed, is restored under the id it has now. */
    @Test
    fun aSavedArticleMovedWhileTheAppWasClosedIsRestored() = runVmTest {
        db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") }
        val dir = FileIO.join(AppDirs.tempDir(), "home-vm-article-rekey-${Random.nextInt()}")
        LocalSettingsStore(dirOverride = dir).save(LocalSettings(lastFilter = "all", lastArticleId = "a1"))

        val vm = newViewModel(settingsDir = dir)

        assertEquals("a0", vm.selectedArticleId.value)
        assertEquals(true, vm.articleRestoredOnLaunch)
        // An id a UI saved with it (the SwiftUI reader's scroll position) is translated the same way.
        assertEquals("a0", vm.currentArticleId("a1"))
        assertEquals("other", vm.currentArticleId("other"))
    }

    /** An in-session move is translated too, for a UI comparing an article id it held across the move. */
    @Test
    fun currentArticleIdTranslatesAnIdASelectionMovedFrom() = runVmTest {
        val vm = newViewModel()
        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()

        mergeF1IntoF0()

        assertEquals("a0", vm.currentArticleId("a1"))
    }

    /**
     * The selection's body lookup resolves the article (a2 -> a1) and is then held before it returns,
     * while a second merge moves that row on (a1 -> a0) and the ViewModel carries every held id onto it
     * (a2 -> a0). The late lookup result names a1, which is no longer the current id: the selection, its
     * cursor and the saved article stay on a0 instead of being moved back onto a1.
     */
    @Test
    fun aLookupThatReadAnIdAMergeThenMovedOnLeavesTheSelectionOnTheCurrentId() = runVmTest {
        db.insertFeed("f2")
        db.insertArticle("a2", "f2", guid = "g", publishedAt = 500L)
        // A pool: the ViewModel's flows run on this dispatcher too, so one parked thread must not stop them.
        val lookupThreads = Executors.newCachedThreadPool()
        try {
            val vm = newViewModel(dispatcher = lookupThreads.asCoroutineDispatcher())
            val row = vm.row("a2")
            // a2 -> a1 is already in the database (the lists have not been told), so the lookup resolves it.
            db.transaction { FeedIdentitySql.mergeLocally(driver, "f2", "f1") }
            val read = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            (driver as ArticleLookupHookDriver).afterNextArticleLookup = {
                read.countDown()
                check(proceed.await(10, TimeUnit.SECONDS)) { "the test never let the lookup return" }
            }
            vm.selectArticle(row)
            check(read.await(10, TimeUnit.SECONDS)) { "the lookup never ran" }

            // The lookup holds a1. Now a1 moves on, and the ViewModel learns of it before the lookup returns.
            db.transaction { FeedIdentitySql.mergeLocally(driver, "f1", "f0") }
            driver.notifyListeners("feeds", "articles", "feed_tags", "feed_fetch_state")
            testScheduler.advanceUntilIdle()
            assertEquals("a0", vm.currentArticleId("a2"), "precondition: the held id moved to a0 while the lookup waited")

            proceed.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (vm.selectedArticle.value == null && System.nanoTime() < deadline) {
                testScheduler.advanceUntilIdle()
                Thread.sleep(10)
            }
            testScheduler.advanceUntilIdle()

            assertEquals("a0", vm.selectedArticleId.value)
            assertEquals("a0", vm.selectedArticle.value?.id)
            assertEquals("a0", vm.currentArticleId("a2"))
        } finally {
            lookupThreads.shutdownNow()
        }
    }
}

/**
 * Passes every statement through to [delegate]; [afterNextArticleLookup], when set, runs once, right
 * after the next `articles.getByIdResolved` statement has read its row (on the thread that ran it) —
 * the one point at which a test can hold a selection's body lookup between its read and its return.
 */
private class ArticleLookupHookDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    @Volatile var afterNextArticleLookup: (() -> Unit)? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        if ("ks_id_remap" in sql && "FROM articles" in sql && "WHERE id = COALESCE" in sql) {
            afterNextArticleLookup?.also { afterNextArticleLookup = null }?.invoke()
        }
        return result
    }
}
