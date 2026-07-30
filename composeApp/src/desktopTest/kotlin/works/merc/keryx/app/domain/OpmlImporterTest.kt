package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal valid RSS document with a single article. */
private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/** A single distinctively-titled article, for FTS search regression tests. */
private const val RSS_WITH_SEARCHABLE_ARTICLE = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Kotlin Multiplatform News</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/** A second, distinctively-titled article for a different feed URL than [RSS_WITH_SEARCHABLE_ARTICLE]. */
private const val RSS_WITH_ANOTHER_SEARCHABLE_ARTICLE = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed B</title><link>https://ex.com</link>
<item><title>Compose Rendering Pipeline</title><link>https://ex.com/b1</link><guid>gb1</guid></item>
</channel></rss>"""

/** An OPML document listing one feed that fetches fine and one whose URL always 410s. */
private const val OPML_ONE_OK_ONE_GONE = """<?xml version="1.0"?>
<opml version="2.0">
<body>
<outline text="Feed" xmlUrl="https://ex.com/feed"/>
<outline text="Gone" xmlUrl="https://ex.com/gone"/>
</body>
</opml>"""

/** An OPML document listing two feeds, each with one distinctively-titled searchable article. */
private const val OPML_TWO_SEARCHABLE_FEEDS = """<?xml version="1.0"?>
<opml version="2.0">
<body>
<outline text="Feed A" xmlUrl="https://ex.com/feed-a"/>
<outline text="Feed B" xmlUrl="https://ex.com/feed-b"/>
</body>
</opml>"""

/** A [NotificationMessages] fake returning canned, recognizable strings. */
private class OpmlImporterTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: works.merc.keryx.app.core.KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

class OpmlImporterTest {

    private fun fetcherWith(handler: MockRequestHandler): FeedFetcher {
        val client = HttpClient(MockEngine(handler)) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** A [FaviconResolver] whose HTTP calls always fail, so resolve()/isReachable() are cheap no-ops. */
    private fun missingFaviconResolver(): FaviconResolver {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FaviconResolver(client)
    }

    /** A [FeedFetcher] that answers every request with a minimal valid RSS document. */
    private fun rssFetcher(): FeedFetcher {
        val client = HttpClient(MockEngine { respond(RSS, HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** A [FeedFetcher] that permanently redirects [from] to [to], then answers with a minimal RSS document. */
    private fun redirectingFetcher(from: String, to: String): FeedFetcher {
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString() == from) {
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, to))
                } else {
                    respond(RSS, HttpStatusCode.OK)
                }
            },
        ) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** The names of the tags currently attached to [feedId]. */
    private fun tagNamesOf(db: KeryxDatabase, feedId: String): Set<String> {
        val namesById = db.tagsQueries.watchAll().executeAsList().associate { it.id to it.name }
        return db.feed_tagsQueries.watchTagIdsForFeed(feedId).executeAsList()
            .mapNotNull { namesById[it] }
            .toSet()
    }

    private fun newImporter(
        db: KeryxDatabase,
        driver: app.cash.sqldelight.db.SqlDriver,
        feedFetcher: FeedFetcher,
        syncScheduler: SyncScheduler = SyncScheduler {},
        clock: Clock = Clock { 1000L },
    ): OpmlImporter {
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so the refresh path's indexMissing() works.
        val ftsManager = FtsManager(driver).also { it.ensureIndexed() }
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManager, syncScheduler,
            NotificationCenter(), OpmlImporterTestNotificationMessages(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        return OpmlImporter(feedRepository, folderRepository, tagRepository)
    }

    @Test
    fun importSubscribesToEveryListedFeedAndCountsSuccessesAndFailures(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { request ->
                if (request.url.toString().endsWith("/gone")) {
                    respond("", HttpStatusCode.Gone)
                } else {
                    respond(RSS, HttpStatusCode.OK)
                }
            }
            val importer = newImporter(db, driver, fetcher)

            val outcome = importer.import(OPML_ONE_OK_ONE_GONE)

            assertEquals(OpmlImportOutcome(added = 1, failed = 1), outcome)
            assertEquals(listOf("https://ex.com/feed"), db.feedsQueries.getAllIncludingDeleted().executeAsList().map { it.url })
        } finally {
            driver.close()
        }
    }

    // MockEngine dispatches its HTTP calls off the calling coroutine's thread, so a plain
    // yield() on the test thread cannot reliably observe whether import B has been let past the
    // mutex — B may already be progressing on another thread. Instead, B's fetch handler signals
    // a bStarted deferred the instant it runs, and the test asserts that signal does NOT arrive
    // within a generous bounded wait while A still holds the lock, then DOES arrive once A
    // releases it — a direct, dispatcher-agnostic observation of mutual exclusion.
    @Test
    fun concurrentImportsSharingAFolderNameSerializeAndDoNotThrow(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val aStarted = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            val bStarted = CompletableDeferred<Unit>()
            // Feed A's fetch parks mid-request until the test releases it; feed B's fetch signals
            // bStarted the instant it runs and resolves immediately, so if OpmlImporter.import()
            // were NOT serialized, B would be free to start (and finish) its own run — including
            // creating the "Tech" folder — while A is still stuck.
            val fetcher = fetcherWith { request ->
                when {
                    request.url.toString().endsWith("/a") -> {
                        aStarted.complete(Unit)
                        releaseA.await()
                    }
                    request.url.toString().endsWith("/b") -> bStarted.complete(Unit)
                }
                respond(RSS, HttpStatusCode.OK)
            }
            val importer = newImporter(db, driver, fetcher)
            val xmlA = """
                <opml version="2.0"><body>
                  <outline text="Tech"><outline type="rss" text="A" xmlUrl="https://ex.com/a"/></outline>
                </body></opml>
            """.trimIndent()
            val xmlB = """
                <opml version="2.0"><body>
                  <outline text="Tech"><outline type="rss" text="B" xmlUrl="https://ex.com/b"/></outline>
                </body></opml>
            """.trimIndent()

            val jobA = async { importer.import(xmlA) }
            aStarted.await() // Import A now holds the mutex, parked mid-fetch for /a.
            val jobB = async { importer.import(xmlB) }

            // B should be blocked acquiring the mutex, so its fetch must not start within a
            // generous window while A is still parked.
            assertNull(withTimeoutOrNull(200) { bStarted.await() })

            releaseA.complete(Unit)
            val resultA = jobA.await()
            // Now that A has released the mutex, B must be able to proceed.
            withTimeout(1000) { bStarted.await() }
            val resultB = jobB.await()

            assertEquals(OpmlImportOutcome(added = 1, failed = 0), resultA)
            assertEquals(OpmlImportOutcome(added = 1, failed = 0), resultB)
            // Exactly one "Tech" folder despite two concurrent runs both resolving that name — the
            // pre-fix race could throw a UNIQUE constraint violation here instead.
            assertEquals(listOf("Tech"), db.foldersQueries.watchAll().executeAsList().map { it.name })
        } finally {
            driver.close()
        }
    }

    @Test
    fun importMakesEveryImportedFeedsArticlesSearchable(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { request ->
                if (request.url.toString().endsWith("/feed-b")) {
                    respond(RSS_WITH_ANOTHER_SEARCHABLE_ARTICLE, HttpStatusCode.OK)
                } else {
                    respond(RSS_WITH_SEARCHABLE_ARTICLE, HttpStatusCode.OK)
                }
            }
            val importer = newImporter(db, driver, fetcher)

            val outcome = importer.import(OPML_TWO_SEARCHABLE_FEEDS)
            assertEquals(OpmlImportOutcome(added = 2, failed = 0), outcome)

            // Regression test: import defers FTS indexing to run once after the whole loop
            // (instead of once per feed) — this must still leave every imported feed's articles
            // searchable, not just the last one indexed.
            val searchRepo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L }, Dispatchers.Unconfined)
            assertEquals(listOf("Kotlin Multiplatform News"), searchRepo.search("Kotlin").map { it.article.title })
            assertEquals(listOf("Compose Rendering Pipeline"), searchRepo.search("Compose").map { it.article.title })
        } finally {
            driver.close()
        }
    }

    @Test
    fun importOnUnparseableXmlSubscribesToNothing(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val importer = newImporter(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) })

            val outcome = importer.import("not opml at all")

            assertEquals(OpmlImportOutcome(added = 0, failed = 0), outcome)
            assertTrue(db.feedsQueries.getAllIncludingDeleted().executeAsList().isEmpty())
        } finally {
            driver.close()
        }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler — subscribeFeed performs
    // (mocked) HTTP calls with HttpTimeout installed, which runTest's virtual time can trip into a
    // false timeout (see docs/testing.md).
    @Test
    fun importRecreatesFoldersAndTagsFromNestedOpml(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val importer = newImporter(db, driver, rssFetcher())
            val xml = """
                <opml version="2.0"><body>
                  <outline text="Tech">
                    <outline type="rss" text="A" xmlUrl="https://a.com/feed" category="kotlin,news"/>
                    <outline type="rss" text="B" xmlUrl="https://b.com/feed"/>
                  </outline>
                  <outline type="rss" text="C" xmlUrl="https://c.com/feed" category="kotlin"/>
                </body></opml>
            """.trimIndent()

            val result = importer.import(xml)

            assertEquals(3, result.added)
            assertEquals(0, result.failed)
            val folders = db.foldersQueries.watchAll().executeAsList()
            assertEquals(listOf("Tech"), folders.map { it.name })
            val a = db.feedsQueries.getByUrl("https://a.com/feed").executeAsOne()
            val b = db.feedsQueries.getByUrl("https://b.com/feed").executeAsOne()
            val c = db.feedsQueries.getByUrl("https://c.com/feed").executeAsOne()
            assertEquals(folders.single().id, a.folder_id)
            assertEquals(folders.single().id, b.folder_id)
            assertNull(c.folder_id)
            // "kotlin" is shared by two feeds but resolved to a single tag row.
            assertEquals(setOf("kotlin", "news"), db.tagsQueries.watchAll().executeAsList().map { it.name }.toSet())
            assertEquals(setOf("kotlin", "news"), tagNamesOf(db, a.id))
            assertEquals(emptySet(), tagNamesOf(db, b.id))
            assertEquals(setOf("kotlin"), tagNamesOf(db, c.id))
        } finally {
            driver.close()
        }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // importRecreatesFoldersAndTagsFromNestedOpml above.
    @Test
    fun importOverwritesAnAlreadySubscribedFeedsFolderAndTagsToMatchTheFile(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val importer = newImporter(db, driver, rssFetcher())
            val first = """
                <opml version="2.0"><body>
                  <outline text="Tech">
                    <outline type="rss" text="A" xmlUrl="https://a.com/feed" category="kotlin,news"/>
                  </outline>
                </body></opml>
            """.trimIndent()
            importer.import(first)
            val feedId = db.feedsQueries.getByUrl("https://a.com/feed").executeAsOne().id
            assertNotNull(db.feedsQueries.getById(feedId).executeAsOne().folder_id)

            // Re-import with the feed moved out of its folder and only one of the two tags kept.
            val second = """
                <opml version="2.0"><body>
                  <outline type="rss" text="A" xmlUrl="https://a.com/feed" category="news"/>
                </body></opml>
            """.trimIndent()
            val result = importer.import(second)

            assertEquals(1, result.added)
            assertNull(db.feedsQueries.getById(feedId).executeAsOne().folder_id)
            assertEquals(setOf("news"), tagNamesOf(db, feedId))
        } finally {
            driver.close()
        }
    }

    @Test
    fun importAppliesFolderAndTagsEvenWhenSubscribeFollowsARedirect(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val importer = newImporter(db, driver, redirectingFetcher("https://old.com/feed", "https://new.com/feed"))
            val xml = """
                <opml version="2.0"><body>
                  <outline text="Tech">
                    <outline type="rss" text="A" xmlUrl="https://old.com/feed" category="kotlin"/>
                  </outline>
                </body></opml>
            """.trimIndent()

            val result = importer.import(xml)

            assertEquals(1, result.added)
            val feed = db.feedsQueries.getByUrl("https://new.com/feed").executeAsOne()
            assertEquals(setOf("Tech"), db.foldersQueries.watchAll().executeAsList().map { it.name }.toSet())
            assertNotNull(feed.folder_id)
            assertEquals(setOf("kotlin"), tagNamesOf(db, feed.id))
        } finally {
            driver.close()
        }
    }
}
