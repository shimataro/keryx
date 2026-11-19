package works.merc.keryx.app.presentation.home

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.LocalWriteFailureKind
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.LocalWriteFailureReporter
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A read/star write that fails off the UI thread is reported, and the optimistic state it stood
 * behind — the row's pin, the selection, a loading selection's read intent — goes back to what the
 * DB holds, unless the user has acted on the same article again since.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelWriteFailureTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var driver: WriteFailingSqlDriver
    private lateinit var db: KeryxDatabase
    private val notifications = NotificationCenter()
    private val fixtures = mutableListOf<HomeViewModelFixture>()

    @BeforeTest
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val (d, _) = inMemoryDb()
        driver = WriteFailingSqlDriver(d)
        db = KeryxDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * [runTest], closing every fixture [newViewModel] built before it returns: the ViewModel's
     * coroutines resume on Main, which only runTest's scheduler advances, so closing it (cancel and
     * join) after the test would wait for them forever.
     */
    private fun runVmTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            fixtures.forEach { it.close() }
        }
    }

    private fun TestScope.newViewModel(
        vararg ids: String,
        starred: Set<String> = emptySet(),
        hydration: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        dbWriteDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): HomeViewModel {
        db.insertFeed("f1")
        ids.forEachIndexed { index, id ->
            val order = (ids.size - index).toLong()
            db.articlesQueries.insert(
                id = id, feed_id = "f1", guid = id, url = "https://article/$id", title = "Title $id",
                summary = null, content = "<p>$id</p>", author = null, published_at = order,
                thumbnail_url = null, is_read = 0L, read_at = null, is_starred = if (id in starred) 1L else 0L,
                starred_at = null, cached_at = 0L, search_text = id, updated_at = 0L, created_at = order,
            )
        }
        val fixture = newHomeViewModel(
            driver, db,
            clock = Clock { 100L },
            dispatcher = hydration,
            dbWriteDispatcher = dbWriteDispatcher,
            localWriteFailureReporter = LocalWriteFailureReporter(notifications, Clock { 100L }),
        )
        fixtures += fixture
        backgroundScope.launch { fixture.vm.articles.collect {} }
        backgroundScope.launch { fixture.vm.selectedArticleRow.collect {} }
        backgroundScope.launch { fixture.vm.selectedArticleShownUnread.collect {} }
        testScheduler.advanceUntilIdle()
        return fixture.vm
    }

    private fun HomeViewModel.row(id: String) = articles.value.single { it.id == id }

    private fun dbIsRead(id: String) = db.articlesQueries.getById(id).executeAsOne().is_read

    private fun dbIsStarred(id: String) = db.articlesQueries.getById(id).executeAsOne().is_starred

    private fun failReadWrites() {
        driver.failExecute = { it.startsWith("UPDATE articles SET is_read") }
    }

    private fun failStarWrites() {
        driver.failExecute = { it.startsWith("UPDATE articles SET is_starred") }
    }

    private fun assertOneLocalWriteFailure() {
        val n = notifications.items.value.single()
        assertEquals(AppNotificationLevel.ERROR, n.level)
        assertEquals(NotificationText.LocalWriteFailed(LocalWriteFailureKind.OTHER), n.text)
    }

    @Test
    fun aFailedMarkUnreadPutsTheRowAndTheSelectionBackToReadAndReportsIt() = runVmTest {
        val vm = newViewModel("a1", "a2")
        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()
        failReadWrites()

        vm.setRead(vm.row("a1"), read = false)
        testScheduler.advanceUntilIdle()

        assertEquals(1L, dbIsRead("a1"))
        assertEquals(1L, vm.row("a1").is_read)
        assertEquals(1L, vm.selectedArticle.value?.is_read)
        assertEquals(1L, vm.selectedArticleRow.value?.is_read)
        assertFalse(vm.selectedArticleShownUnread.value)
        assertOneLocalWriteFailure()
    }

    @Test
    fun aFailedSelectionReadLeavesTheRowUnreadAndStillListedUnderUnreadOnly() = runVmTest {
        val vm = newViewModel("a1", "a2")
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        failReadWrites()

        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()

        assertEquals(0L, dbIsRead("a1"))
        assertEquals(0L, vm.row("a1").is_read, "the row stays, unread, rather than dropping out of the list")
        assertEquals("a1", vm.selectedArticle.value?.id)
        assertEquals(0L, vm.selectedArticle.value?.is_read)
        assertTrue(vm.selectedArticleShownUnread.value)
        assertOneLocalWriteFailure()
    }

    @Test
    fun aFailedSelectionReadIsUndoneForASelectionStillLoadingItsBody() = runVmTest {
        val hydration = HoldingDispatcher()
        val vm = newViewModel("a1", "a2", hydration = hydration)
        failReadWrites()

        hydration.hold()
        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()
        hydration.release()
        testScheduler.advanceUntilIdle()

        assertEquals(0L, dbIsRead("a1"))
        assertEquals("a1", vm.selectedArticle.value?.id)
        assertEquals(0L, vm.selectedArticle.value?.is_read, "the loaded selection shows the DB's unread, not the failed read")
        assertEquals(0L, vm.row("a1").is_read)
    }

    @Test
    fun aFailedUnstarUnderTheStarredFilterKeepsTheRowStarred() = runVmTest {
        val vm = newViewModel("a1", "a2", starred = setOf("a1"))
        vm.selectFilter(ArticleFilter.Starred)
        testScheduler.advanceUntilIdle()
        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()
        failStarWrites()

        vm.setStarred(vm.row("a1"), starred = false)
        testScheduler.advanceUntilIdle()

        assertEquals(1L, dbIsStarred("a1"))
        assertEquals(1L, vm.row("a1").is_starred)
        assertEquals(1L, vm.selectedArticle.value?.is_starred)
        assertEquals(1L, vm.selectedArticleRow.value?.is_starred)
        assertOneLocalWriteFailure()
    }

    @Test
    fun aFailedMarkAllReadPutsEveryRowBackToUnread() = runVmTest {
        val vm = newViewModel("a1", "a2")
        vm.setUnreadOnly(true)
        testScheduler.advanceUntilIdle()
        failReadWrites()

        vm.markAllRead()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(0L, 0L), listOf(dbIsRead("a1"), dbIsRead("a2")))
        assertEquals(listOf("a1" to 0L, "a2" to 0L), vm.articles.value.map { it.id to it.is_read })
        assertOneLocalWriteFailure()
    }

    @Test
    fun aNewerActionOnTheSameArticleIsNotOverwrittenByTheRevert() = runVmTest {
        val writes = ParkingWriteDispatcher()
        val vm = newViewModel("a1", "a2", dbWriteDispatcher = writes)
        vm.selectArticle(vm.row("a1"))
        testScheduler.advanceUntilIdle()
        failStarWrites()

        // Released on every way out, so a failed assertion cannot leave the ViewModel's coroutines
        // queued on it (closing the fixture would then wait for them forever).
        try {
            writes.hold()
            vm.setStarred(vm.row("a1"), starred = true)
            // The star write runs and fails; its revert's re-read is now queued behind the writes.
            writes.drain()
            testScheduler.advanceUntilIdle()
            driver.failExecute = null
            // The user stars it again before the revert has applied; that write lands after the re-read.
            vm.setStarred(vm.row("a1"), starred = true)
            val newer = writes.parkLast()
            writes.drain()
            testScheduler.advanceUntilIdle()

            assertEquals(1L, vm.selectedArticle.value?.is_starred, "the newer star still shows")

            writes.run(newer)
            writes.release()
            testScheduler.advanceUntilIdle()

            assertEquals(1L, dbIsStarred("a1"))
            assertEquals(1L, vm.selectedArticle.value?.is_starred)
            assertEquals(1L, vm.row("a1").is_starred)
        } finally {
            writes.release()
        }
    }
}

/** Fails every statement [failExecute] matches, as a locked database would — lets a test fail a write. */
private class WriteFailingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    @kotlin.concurrent.Volatile var failExecute: ((sql: String) -> Boolean)? = null

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (failExecute?.invoke(sql.trimStart()) == true) throw IllegalStateException("database is locked")
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

/**
 * Runs each dispatched block at once, except while holding — then queues it, so a test can run
 * the queue ([drain]) or set its last block aside ([parkLast]) and run it later ([run]).
 */
private class ParkingWriteDispatcher : CoroutineDispatcher() {
    private val lock = Any()
    private val queue = ArrayDeque<Runnable>()
    private val parked = mutableListOf<Runnable>()
    private var holding = false

    fun hold() {
        synchronized(lock) { holding = true }
    }

    fun parkLast(): Runnable = synchronized(lock) { queue.removeLast().also { parked += it } }

    fun drain() {
        while (true) {
            val next = synchronized(lock) { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }

    fun run(block: Runnable) {
        synchronized(lock) { check(parked.remove(block)) { "not parked" } }
        block.run()
    }

    fun release() {
        val drained = synchronized(lock) {
            holding = false
            (parked + queue).also { parked.clear(); queue.clear() }
        }
        drained.forEach { it.run() }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val queued = synchronized(lock) { holding.also { if (it) queue.addLast(block) } }
        if (!queued) block.run()
    }
}
