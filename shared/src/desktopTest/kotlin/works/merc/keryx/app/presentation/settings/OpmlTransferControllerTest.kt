package works.merc.keryx.app.presentation.settings

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ONE_FEED_OPML = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

/**
 * Runs each block at once, except while [hold]ing — then queues it until [release]. Lets a test keep
 * an import that has already been cancelled from finishing, to observe who waits for it.
 */
private class ImportHoldingDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    @Volatile
    private var holding = false
    val queuedCount: Int get() = queue.size

    fun hold() {
        holding = true
    }

    fun release() {
        holding = false
        while (true) (queue.poll() ?: return).run()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (holding) queue.add(block) else block.run()
    }
}

/**
 * [OpmlTransferController] is the one OPML busy/result/request state every route (the Data tab, the
 * File menu, an opened `.opml` file) and every UI shares, so these cover the guarantees those routes
 * rely on: busy for the whole run, no overlap, a request latched until the Data tab takes it (and
 * never handed out mid-run), and a stale result never outliving the next operation's start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpmlTransferControllerTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    /** The app scopes handed to the controllers under test, cancelled before the driver closes. */
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        driver.close()
    }

    private fun appScope(job: Job = SupervisorJob(), handler: CoroutineExceptionHandler? = null): CoroutineScope {
        val context = job + Dispatchers.Unconfined
        return CoroutineScope(if (handler == null) context else context + handler).also { scopes += it }
    }

    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
        <title>Feed</title><link>https://ex.com</link>
        <item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
        </channel></rss>"""

    /**
     * A fetcher whose every response waits for [gate], so an import stays in flight until the test
     * releases it; [started] completes once a request has reached it.
     */
    private fun fetcher(gate: CompletableDeferred<Unit>? = null, started: CompletableDeferred<Unit>? = null): FeedFetcher {
        val client = HttpClient(
            MockEngine {
                started?.complete(Unit)
                gate?.await()
                respond(rss, HttpStatusCode.OK)
            },
        ) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    private fun controller(
        feedFetcher: FeedFetcher = fetcher(),
        scope: CoroutineScope = appScope(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): OpmlTransferController {
        val clock = Clock { 0L }
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        val faviconClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val feedRepository = FeedRepository(
            db, feedFetcher, FaviconResolver(faviconClient), articleRepository, ftsManagerIndexed(driver), syncScheduler,
            NotificationCenter(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val transfer = OpmlTransfer(feedRepository, folderRepository, tagRepository, OpmlImporter(feedRepository, folderRepository, tagRepository))
        return OpmlTransferController(transfer, scope, dispatcher)
    }

    /** Polls in real time: the import's fetch runs on the HTTP engine's own threads. */
    private suspend fun awaitTrue(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    @Test
    fun importDocumentIsBusyForTheWholeRunAndReportsTheOutcome() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(fetcher(gate))

        val run = launch { controller.importDocument(ONE_FEED_OPML) }
        runCurrent()
        assertTrue(controller.busy.value)
        assertEquals(OpmlOperation.Importing, controller.running.value)
        assertNull(controller.result.value)

        gate.complete(Unit)
        run.join()

        assertFalse(controller.busy.value)
        assertNull(controller.running.value)
        assertEquals(OpmlResult.Imported(added = 1, failed = 0), controller.result.value)
    }

    @Test
    fun aSecondOperationIsRefusedWhileOneRuns() {
        val controller = controller()

        assertTrue(controller.tryBegin(OpmlOperation.Importing))
        assertFalse(controller.tryBegin(OpmlOperation.Exporting))
        assertFalse(controller.tryBegin(OpmlOperation.Importing))
        assertEquals(OpmlOperation.Importing, controller.running.value)

        controller.finish(null)
        assertTrue(controller.tryBegin(OpmlOperation.Exporting))
    }

    @Test
    fun importDocumentDoesNothingWhileAnotherOperationRuns() = runTest {
        val controller = controller()
        controller.tryBegin(OpmlOperation.Exporting)

        assertFalse(controller.importDocument(ONE_FEED_OPML))
        assertEquals(OpmlOperation.Exporting, controller.running.value)
        assertNull(controller.result.value)
    }

    @Test
    fun aRequestIsLatchedUntilConsumed() {
        val controller = controller()

        controller.request(OpmlRequest.ImportFile)
        assertEquals(OpmlRequest.ImportFile, controller.pendingRequest.value)

        assertEquals(OpmlRequest.ImportFile, controller.consumeRequest())
        assertNull(controller.pendingRequest.value)
        assertNull(controller.consumeRequest(), "handed out only once")
    }

    @Test
    fun theLastRequestWins() {
        val controller = controller()

        controller.request(OpmlRequest.ImportFile)
        controller.request(OpmlRequest.ImportDocument("<opml/>"))

        assertEquals(OpmlRequest.ImportDocument("<opml/>"), controller.consumeRequest())
    }

    @Test
    fun aRequestIsNotConsumableWhileBusy() {
        val controller = controller()
        controller.tryBegin(OpmlOperation.Importing)
        controller.request(OpmlRequest.ExportFile)

        assertNull(controller.consumeRequest())
        assertEquals(OpmlRequest.ExportFile, controller.pendingRequest.value, "kept for after the running operation")

        controller.finish(null)
        assertEquals(OpmlRequest.ExportFile, controller.consumeRequest())
    }

    @Test
    fun anUnreadableDocumentFinishesAsImportFailed() = runTest {
        val controller = controller()

        assertTrue(controller.importDocument(null))

        assertEquals(OpmlResult.ImportFailed, controller.result.value)
        assertFalse(controller.busy.value)
    }

    @Test
    fun startingANewOperationClearsThePreviousResult() = runTest {
        val controller = controller()
        controller.importDocument(null)
        assertEquals(OpmlResult.ImportFailed, controller.result.value)

        controller.tryBegin(OpmlOperation.Exporting)

        assertNull(controller.result.value)
    }

    @Test
    fun aResultIsKeptUntilCleared() = runTest {
        val controller = controller()
        controller.importDocument(null)

        assertEquals(OpmlResult.ImportFailed, controller.result.value, "kept for the next Data tab visit")
        controller.clearResult()
        assertNull(controller.result.value)
    }

    @Test
    fun cancellingTheCallerWaitsForTheImportToStopBeforeRethrowing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val importDispatcher = ImportHoldingDispatcher()
        val scopeJob = SupervisorJob()
        val controller = controller(fetcher(gate = CompletableDeferred(), started = started), appScope(scopeJob), importDispatcher)
        var thrown: Throwable? = null
        val caller = launch(Dispatchers.Default) {
            try {
                controller.importResult(ONE_FEED_OPML)
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        withTimeout(5_000) { started.await() }

        // Keep the import from finishing its own cancellation until released.
        importDispatcher.hold()
        caller.cancel()
        awaitTrue { importDispatcher.queuedCount > 0 }
        delay(50)
        assertFalse(caller.isCompleted, "the caller must wait for the import to stop")
        assertEquals(1, scopeJob.children.count(), "the import is still running on the app scope")

        importDispatcher.release()
        caller.join()

        assertIs<CancellationException>(thrown)
        assertEquals(0, scopeJob.children.count(), "the import had stopped by the time the caller finished")
    }

    @Test
    fun cancellingTheAppScopeCancelsARunningImport() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val scope = appScope()
        val controller = controller(fetcher(gate = CompletableDeferred(), started = started), scope)
        val run = launch(Dispatchers.Default) {
            assertFailsWith<CancellationException> { controller.importResult(ONE_FEED_OPML) }
        }
        withTimeout(5_000) { started.await() }

        scope.cancel()

        withTimeout(5_000) { run.join() }
        assertFalse(run.isCancelled, "the caller itself was not cancelled, yet its import was")
    }

    @Test
    fun importResultOnAClosedAppScopeThrowsCancellation() = runTest {
        val scope = appScope()
        val controller = controller(scope = scope)
        scope.cancel()

        assertFailsWith<CancellationException> { controller.importResult(ONE_FEED_OPML) }
    }

    @Test
    fun aFailingImportBecomesImportFailedWithoutReachingTheScopesExceptionHandler() = runTest {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val controller = controller(scope = appScope(handler = CoroutineExceptionHandler { _, e -> uncaught += e }))
        // An unexpected DB failure inside the import.
        driver.execute(null, "DROP TABLE feed_tags", 0)

        assertEquals(OpmlResult.ImportFailed, controller.importResult(ONE_FEED_OPML))
        assertTrue(uncaught.isEmpty(), "the failure is the result, not an uncaught exception: $uncaught")
    }

    @Test
    fun busyAlwaysMatchesWhetherAnOperationIsRunning() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller()
        val seen = mutableListOf<Boolean>()
        val collecting = launch { controller.busy.toList(seen) }
        assertFalse(controller.busy.value)

        assertTrue(controller.tryBegin(OpmlOperation.Importing))
        assertTrue(controller.busy.value, "true immediately after tryBegin")
        assertFalse(controller.tryBegin(OpmlOperation.Exporting))
        assertTrue(controller.busy.value)

        controller.finish(null)
        assertFalse(controller.busy.value, "false immediately after finish")

        collecting.cancel()
        assertEquals(listOf(false, true, false), seen)
    }
}
