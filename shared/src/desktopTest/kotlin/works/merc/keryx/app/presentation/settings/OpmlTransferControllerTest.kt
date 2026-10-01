package works.merc.keryx.app.presentation.settings

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ONE_FEED_OPML = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

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

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
        <title>Feed</title><link>https://ex.com</link>
        <item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
        </channel></rss>"""

    /** A fetcher whose every response waits for [gate], so an import stays in flight until the test releases it. */
    private fun fetcher(gate: CompletableDeferred<Unit>? = null): FeedFetcher {
        val client = HttpClient(
            MockEngine {
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

    private fun controller(feedFetcher: FeedFetcher = fetcher()): OpmlTransferController {
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
        return OpmlTransferController(transfer, Dispatchers.Unconfined)
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
}
