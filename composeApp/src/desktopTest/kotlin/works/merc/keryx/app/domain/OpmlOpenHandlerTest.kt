package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A minimal valid RSS document with no articles, for a cheap successful subscribe. */
private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
</channel></rss>"""

private const val OPML_ONE_FEED = """<?xml version="1.0"?>
<opml version="2.0"><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

/** A [NotificationMessages] fake returning a canned, recognizable string. */
private class OpmlOpenHandlerTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = error("not used")
    override suspend fun feedUrlChanged(feedTitle: String): String = error("not used")
    override suspend fun newArticles(count: Int): String = error("not used")
    override suspend fun syncFailed(exception: KeryxException): String = error("not used")
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

/**
 * Verifies [importOpmlAndNotify] — the platform-independent half of the `.opml` "open with Keryx"
 * flow shared by desktop's `handleOpenedOpmlFile` and Android's `handleOpmlOpenIfPresent`. The
 * import logic itself ([OpmlImporter.import]'s subscribe/folder/tag reconciliation) is already
 * exhaustively covered by `OpmlImporterTest`; this only covers the thin orchestration layer this
 * function adds on top: recording the outcome in the notification center, and not propagating a
 * failure to resolve its own dependencies.
 */
class OpmlOpenHandlerTest {

    /** An isolated Koin instance with a real [OpmlImporter] (backed by [db]/[driver]) — no `startKoin()`. */
    private fun testKoin(db: works.merc.keryx.app.data.local.db.KeryxDatabase, driver: app.cash.sqldelight.db.SqlDriver): Koin {
        val client = HttpClient(MockEngine { respond(RSS, HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val clock = Clock { 1000L }
        val faviconClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val ftsManager = works.merc.keryx.app.ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, FeedFetcher(client), FaviconResolver(faviconClient), articleRepository, ftsManager,
            SyncScheduler {}, NotificationCenter(), OpmlOpenHandlerTestNotificationMessages(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, feedRepository, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val importer = OpmlImporter(feedRepository, folderRepository, tagRepository)
        return koinApplication {
            modules(
                module {
                    single { importer }
                    single<NotificationMessages> { OpmlOpenHandlerTestNotificationMessages() }
                    single { NotificationCenter() }
                },
            )
        }.koin
    }

    @Test
    fun recordsAnInfoNotificationWithTheImportOutcome() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val koin = testKoin(db, driver)

            importOpmlAndNotify(koin, OPML_ONE_FEED)

            val notifications = koin.get<NotificationCenter>().items.value
            assertEquals(1, notifications.size)
            assertEquals(AppNotificationLevel.INFO, notifications.single().level)
            assertEquals("opmlImported:1/0", notifications.single().message)
        } finally {
            driver.close()
        }
    }

    @Test
    fun swallowsAFailureToResolveItsOwnDependenciesWithoutCrashingOrNotifying() = runTest {
        // No OpmlImporter/NotificationMessages/NotificationCenter registered: koin.get<OpmlImporter>()
        // throws inside importOpmlAndNotify's own runCatching, which must swallow it and return —
        // this must not crash the caller (an .opml opened with a broken DI graph is a real
        // possibility, not just a test artifact — see the function's own KDoc).
        val koin = koinApplication { modules(module {}) }.koin

        importOpmlAndNotify(koin, OPML_ONE_FEED)
        // Reaching here without an exception is the assertion; there is nothing else to observe
        // since NotificationCenter itself was never registered.
        assertTrue(true)
    }
}
