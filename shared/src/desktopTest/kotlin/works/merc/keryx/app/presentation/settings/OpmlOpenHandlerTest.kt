package works.merc.keryx.app.presentation.settings

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal valid RSS document with no articles, for a cheap successful subscribe. */
private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
</channel></rss>"""

private const val OPML_ONE_FEED = """<?xml version="1.0"?>
<opml version="2.0"><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

/**
 * Verifies [requestOpenedOpmlImport] — the platform-independent half of the `.opml` "open with
 * Keryx" flow shared by desktop's `handleOpenedOpmlFile`, Android's `handleOpmlOpenIfPresent` and
 * `KeryxSdk.importOpenedOpml`. It must only *request* the import (the settings Data tab carries it
 * out, once Home is showing), turn an unreadable file into an inline import failure, and post
 * nothing to the notification center.
 */
class OpmlOpenHandlerTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private lateinit var koin: Koin

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
        koin = testKoin()
    }

    /** Stands in for the app scope the controller runs imports on; cancelled before the driver closes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        driver.close()
    }

    /** An isolated Koin instance with a real [OpmlTransferController] (backed by [db]) — no `startKoin()`. */
    private fun testKoin(): Koin {
        val client = HttpClient(MockEngine { respond(RSS, HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val faviconClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val clock = Clock { 1000L }
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, FeedFetcher(client), FaviconResolver(faviconClient), articleRepository, ftsManagerIndexed(driver),
            SyncScheduler {}, NotificationCenter(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, feedRepository, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val transfer = OpmlTransfer(feedRepository, folderRepository, tagRepository, OpmlImporter(feedRepository, folderRepository, tagRepository))
        return koinApplication {
            modules(
                module {
                    single { OpmlTransferController(transfer, appScope, Dispatchers.Unconfined) }
                    single { NotificationCenter() }
                },
            )
        }.koin
    }

    private val controller get() = koin.get<OpmlTransferController>()

    @Test
    fun theImportIsRequestedNotRunImmediately() {
        requestOpenedOpmlImport(koin, OPML_ONE_FEED)

        assertEquals(OpmlRequest.ImportDocument(OPML_ONE_FEED), controller.pendingRequest.value)
        assertEquals(0, db.feedsQueries.getAllIncludingDeleted().executeAsList().size, "nothing is imported until the Data tab takes it")
        assertNull(controller.result.value)
    }

    @Test
    fun theRequestedDocumentIsImportedOnceConsumed() = runTest {
        requestOpenedOpmlImport(koin, OPML_ONE_FEED)

        val request = controller.consumeRequest() as OpmlRequest.ImportDocument
        assertTrue(controller.importDocument(request.xml))

        assertEquals(OpmlResult.Imported(added = 1, failed = 0), controller.result.value)
    }

    @Test
    fun anUnreadableFileBecomesAnImportFailureOnceConsumed() = runTest {
        requestOpenedOpmlImport(koin, null)

        val request = controller.consumeRequest() as OpmlRequest.ImportDocument
        controller.importDocument(request.xml)

        assertEquals(OpmlResult.ImportFailed, controller.result.value)
    }

    @Test
    fun nothingIsPostedToTheNotificationCenter() = runTest {
        requestOpenedOpmlImport(koin, OPML_ONE_FEED)
        val request = controller.consumeRequest() as OpmlRequest.ImportDocument
        controller.importDocument(request.xml)

        assertTrue(koin.get<NotificationCenter>().items.value.isEmpty(), "the result is shown on the Data tab instead")
    }
}
