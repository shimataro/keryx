package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertTag
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

private const val RSS_UNTITLED = """<?xml version="1.0"?><rss version="2.0"><channel>
<link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

private const val DISCOVERY_HTML = """<html><head>
<link rel="alternate" type="application/rss+xml" href="/feed.xml" title="RSS"/>
<link rel="alternate" type="application/atom+xml" href="/atom.xml" title="Atom"/>
</head><body>site</body></html>"""

/**
 * [AddFeedPreviewResolver] against a real in-memory DB and a MockEngine-backed [FeedFetcher].
 * `runBlocking` rather than `runTest`: MockEngine plus `HttpTimeout` under virtual time can misfire
 * (see testing.md).
 */
class AddFeedPreviewResolverTest {
    private val clients = mutableListOf<HttpClient>()

    private fun client(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }.also { clients += it }

    private fun resolver(
        driver: SqlDriver,
        db: KeryxDatabase,
        handler: MockRequestHandler,
    ): AddFeedPreviewResolver {
        val clock = Clock { 1000L }
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, FeedFetcher(client(handler)), FaviconResolver(client { respond("", HttpStatusCode.NotFound) }),
            articleRepository, ftsManagerIndexed(driver), SyncScheduler {},
            NotificationCenter(), clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
        )
        return AddFeedPreviewResolver(feedRepository, TagRepository(db, SyncScheduler {}, clock, Dispatchers.Unconfined))
    }

    private fun withDb(block: suspend (SqlDriver, KeryxDatabase) -> Unit): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            block(driver, db)
        } finally {
            clients.forEach { it.close() }
            clients.clear()
            driver.close()
        }
    }

    // --- resolvePreview ---

    @Test
    fun aSchemelessInputWhoseHttpsAttemptFindsFeedLinksReturnsMultipleAndNeverTriesHttp() = withDb { driver, db ->
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val resolver = resolver(driver, db) { request ->
            requested += request.url.toString()
            respond(DISCOVERY_HTML, HttpStatusCode.OK)
        }

        val preview = resolver.resolvePreview("ex.com")

        val multiple = assertIs<AddFeedPreview.Multiple>(preview)
        assertEquals(listOf("https://ex.com/feed.xml", "https://ex.com/atom.xml"), multiple.candidates.map { it.url })
        assertEquals(listOf(true), requested.map { it.startsWith("https://") }.distinct(), "no http:// request was ever made")
    }

    @Test
    fun aSchemelessInputWhoseHttpsAndHttpAttemptsBothFailReturnsTheHttpAttemptsError() = withDb { driver, db ->
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val resolver = resolver(driver, db) { request ->
            requested += request.url.protocol.name
            if (request.url.protocol.name == "https") respond("", HttpStatusCode.NotFound)
            else respond("", HttpStatusCode.InternalServerError)
        }

        val preview = resolver.resolvePreview("ex.com/feed")

        val failed = assertIs<AddFeedPreview.Failed>(preview)
        val error = assertIs<FeedFetchException>(failed.exception, "the http attempt's 500, not the https attempt's 404")
        assertEquals(500, error.statusCode)
        assertEquals(listOf("https", "http"), requested.toList())
    }

    @Test
    fun anExplicitSchemeIsNotRetriedOverHttp() = withDb { driver, db ->
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val resolver = resolver(driver, db) { request ->
            requested += request.url.protocol.name
            respond("", HttpStatusCode.NotFound)
        }

        val failed = assertIs<AddFeedPreview.Failed>(resolver.resolvePreview("https://ex.com/feed"))

        assertIs<FeedNotFoundException>(failed.exception)
        assertEquals(listOf("https"), requested.toList())
    }

    @Test
    fun anUntitledFeedFallsBackToTheAttemptedUrlAsItsTitle() = withDb { driver, db ->
        val resolver = resolver(driver, db) { respond(RSS_UNTITLED, HttpStatusCode.OK) }

        val single = assertIs<AddFeedPreview.Single>(resolver.resolvePreview("https://ex.com/feed"))

        assertEquals("https://ex.com/feed", single.resolvedUrl)
        assertEquals("https://ex.com/feed", single.title)
        assertEquals(1, single.articleCount)
    }

    @Test
    fun anUntitledFeedThatOnlyResolvedOverHttpUsesTheHttpUrlAsItsTitle() = withDb { driver, db ->
        val resolver = resolver(driver, db) { request ->
            if (request.url.protocol.name == "https") respond("", HttpStatusCode.NotFound)
            else respond(RSS_UNTITLED, HttpStatusCode.OK)
        }

        val single = assertIs<AddFeedPreview.Single>(resolver.resolvePreview("ex.com/feed"))

        assertEquals("http://ex.com/feed", single.resolvedUrl)
        assertEquals("http://ex.com/feed", single.title)
    }

    // --- subscribeFeeds ---

    private fun orderedUnfiledIds(db: KeryxDatabase) = db.feedsQueries.getByFolder(null).executeAsList().map { it.id }

    @Test
    fun tagIdIsAppliedOnlyToNewlyCreatedFeeds() = withDb { driver, db ->
        db.insertFeed("old", url = "https://ex.com/old", sortOrder = 0L)
        db.insertTag("t1", "Tag")
        val resolver = resolver(driver, db) { respond(RSS, HttpStatusCode.OK) }

        val outcome = resolver.subscribeFeeds(
            listOf("https://ex.com/a", "https://ex.com/old", "https://ex.com/b"),
            tagId = "t1",
        )

        assertEquals(3, outcome.successCount)
        val a = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val b = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        assertEquals(setOf(a.id, b.id), db.feed_tagsQueries.watchFeedIdsForTag("t1").executeAsList().toSet())
        assertEquals(emptyList(), db.feed_tagsQueries.watchTagIdsForFeed("old").executeAsList(), "the existing feed is left untagged")
    }

    @Test
    fun withoutATagIdNothingIsTagged() = withDb { driver, db ->
        db.insertTag("t1", "Tag")
        val resolver = resolver(driver, db) { respond(RSS, HttpStatusCode.OK) }

        resolver.subscribeFeeds(listOf("https://ex.com/a"))

        assertEquals(emptyList(), db.feed_tagsQueries.watchAllActive().executeAsList())
    }

    @Test
    fun theInsertAfterChainAdvancesOnlyThroughNewFeeds() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1", sortOrder = 0L)
        db.insertFeed("old", url = "https://ex.com/old", sortOrder = 1L)
        val resolver = resolver(driver, db) { respond(RSS, HttpStatusCode.OK) }

        // The already-subscribed URL in the middle must not become the anchor for "b".
        val outcome = resolver.subscribeFeeds(
            listOf("https://ex.com/a", "https://ex.com/old", "https://ex.com/b"),
            afterFeedId = "f1",
        )

        assertEquals(3, outcome.successCount)
        val a = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val b = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        assertEquals(listOf("f1", a.id, b.id, "old"), orderedUnfiledIds(db))
    }

    @Test
    fun anAlreadySubscribedFirstUrlDoesNotConsumeTheInsertionPoint() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1", sortOrder = 0L)
        db.insertFeed("old", url = "https://ex.com/old", sortOrder = 1L)
        val resolver = resolver(driver, db) { respond(RSS, HttpStatusCode.OK) }

        resolver.subscribeFeeds(listOf("https://ex.com/old", "https://ex.com/a"), afterFeedId = "f1")

        val a = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        assertEquals(listOf("f1", a.id, "old"), orderedUnfiledIds(db))
    }

    @Test
    fun feedIdsFollowRequestOrderWithTheTallyAndFirstError() = withDb { driver, db ->
        db.insertFeed("old", url = "https://ex.com/old", sortOrder = 0L)
        val resolver = resolver(driver, db) { request ->
            when (request.url.host) {
                "gone.com" -> respond("", HttpStatusCode.NotFound)
                "bad.com" -> respond("", HttpStatusCode.InternalServerError)
                else -> respond(RSS, HttpStatusCode.OK)
            }
        }

        val outcome = resolver.subscribeFeeds(
            listOf("https://ex.com/b", "https://gone.com/feed", "https://ex.com/old", "https://bad.com/feed", "https://ex.com/a"),
        )

        val a = db.feedsQueries.getByUrl("https://ex.com/a").executeAsOne()
        val b = db.feedsQueries.getByUrl("https://ex.com/b").executeAsOne()
        assertEquals(listOf(b.id, "old", a.id), outcome.feedIds)
        assertEquals(3, outcome.successCount)
        assertEquals(2, outcome.failCount)
        assertIs<FeedNotFoundException>(assertNotNull(outcome.firstError), "the first failure in request order, not the last")
    }

    @Test
    fun anAllSuccessfulBatchHasNoFirstError() = withDb { driver, db ->
        val resolver = resolver(driver, db) { respond(RSS, HttpStatusCode.OK) }

        val outcome = resolver.subscribeFeeds(listOf("https://ex.com/a"))

        assertEquals(1, outcome.successCount)
        assertEquals(0, outcome.failCount)
        assertNull(outcome.firstError)
    }
}
