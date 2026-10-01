package works.merc.keryx.app.presentation.settings

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.opml.OpmlCodec
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
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpmlTransferTest {

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

    private fun missingFaviconResolver(): FaviconResolver {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FaviconResolver(client)
    }

    /** A [FeedFetcher] that answers every request with a minimal valid feed, for the import test. */
    private fun succeedingFetcher(): FeedFetcher {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
            <title>Feed</title><link>https://ex.com</link>
            <item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
            </channel></rss>"""
        val client = HttpClient(MockEngine { respond(rss, HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    private fun newTransfer(feedFetcher: FeedFetcher): OpmlTransfer {
        val clock = Clock { 0L }
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so subscribeFeed's indexMissing() works.
        val ftsManager = ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManager, syncScheduler,
            NotificationCenter(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val opmlImporter = OpmlImporter(feedRepository, folderRepository, tagRepository)
        return OpmlTransfer(feedRepository, folderRepository, tagRepository, opmlImporter)
    }

    @Test
    fun exportOpmlGroupsFeedsByFolderInDisplayOrderAndAnnotatesTags() {
        db.insertFolder("d1", "Tech", sortOrder = 0L)
        db.insertFolder("d2", "News", sortOrder = 1L)
        db.insertFolder("d3", "Empty", sortOrder = 2L)
        db.insertFeed("f1", url = "https://a.com/feed", folderId = "d1", sortOrder = 0L)
        db.insertFeed("f2", url = "https://b.com/feed", folderId = "d1", sortOrder = 1L)
        db.insertFeed("f3", url = "https://c.com/feed", folderId = "d2", sortOrder = 0L)
        db.insertFeed("f4", url = "https://d.com/feed", sortOrder = 0L) // unfoldered
        db.insertTag("t1", "kotlin", sortOrder = 0L)
        db.insertTag("t2", "daily", sortOrder = 1L)
        db.insertFeedTag("f1", "t2")
        db.insertFeedTag("f1", "t1")
        val transfer = newTransfer(succeedingFetcher())

        val xml = transfer.exportOpml()

        assertTrue(xml.contains("""<outline text="Tech">"""))
        // Tags follow the tags' own display (sort_order) order, not attachment order.
        assertTrue(xml.contains("""category="kotlin,daily""""))
        // An empty folder has nothing to export, so it is skipped entirely.
        assertFalse(xml.contains("Empty"))

        val reimported = OpmlCodec.import(xml)
        // Folders first in folder sort order, feeds in their sort order within each, unfoldered last.
        assertEquals(
            listOf("https://a.com/feed", "https://b.com/feed", "https://c.com/feed", "https://d.com/feed"),
            reimported.map { it.xmlUrl },
        )
        assertEquals(listOf("Tech", "Tech", "News", null), reimported.map { it.folderName })
        assertEquals(listOf("kotlin", "daily"), reimported[0].tags)
        assertTrue(reimported.drop(1).all { it.tags.isEmpty() })
    }

    @Test
    fun importOpmlSubscribesEveryFeedInTheDocument() = runTest {
        val transfer = newTransfer(succeedingFetcher())
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

        val outcome = transfer.importOpml(xml)

        assertEquals(1, outcome.added)
        assertEquals(0, outcome.failed)
    }
}
