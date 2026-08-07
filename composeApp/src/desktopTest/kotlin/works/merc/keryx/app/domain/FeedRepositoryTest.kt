package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.CountingSqlDriver
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.ftsManagerIndexed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/** A single distinctively-titled article, for FTS search regression tests. */
private const val RSS_WITH_SEARCHABLE_ARTICLE = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Kotlin Multiplatform News</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/** The original article plus a second, distinctively-titled new one (different guid). */
private const val RSS_WITH_NEW_SEARCHABLE_ARTICLE = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
<item><title>Serialization Deep Dive</title><link>https://ex.com/2</link><guid>g2</guid></item>
</channel></rss>"""

/** A [NotificationMessages] fake returning canned, recognizable strings. */
private class FakeNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: works.merc.keryx.app.core.KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

class FeedRepositoryTest {

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

    private fun newRepo(
        db: works.merc.keryx.app.data.local.db.KeryxDatabase,
        driver: app.cash.sqldelight.db.SqlDriver,
        feedFetcher: FeedFetcher,
        faviconResolver: FaviconResolver = missingFaviconResolver(),
        syncScheduler: SyncScheduler = SyncScheduler {},
        notificationCenter: NotificationCenter = NotificationCenter(),
        messages: NotificationMessages = FakeNotificationMessages(),
        clock: Clock = Clock { 1000L },
    ): FeedRepository {
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so the refresh path's indexMissing() works.
        val ftsManager = ftsManagerIndexed(driver)
        return FeedRepository(
            db, feedFetcher, faviconResolver, articleRepository, ftsManager, syncScheduler,
            notificationCenter, messages, clock, Dispatchers.Unconfined,
        )
    }

    @Test
    fun subscribeFeedHappyPathInsertsFeedAndArticlesAndSchedulesSync(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "etag-1")) }
            var syncCount = 0
            val repo = newRepo(db, driver, fetcher, syncScheduler = { syncCount++ })

            val result = repo.subscribeFeed("https://ex.com/feed")

            assertIs<Result.Ok<Feeds>>(result)
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            assertEquals(feed, result.value)
            assertEquals("Feed", feed.title)
            assertEquals("etag-1", feed.etag)
            assertEquals(1, db.articlesQueries.watchAll().executeAsList().size)
            assertEquals(1, syncCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun sameUrlSubscribedOnTwoDevicesGetsSameFeedId(): Unit = runBlocking {
        // Two devices independently subscribing the same feed url must store it under the SAME feed
        // id, otherwise the sync merge (matched by id) can't converge them — and neither feeds nor
        // their articles (whose ids derive from feed_id) sync. Before the deterministic-id fix these
        // feed ids were random and differed.
        suspend fun feedIdForSameUrl(): String {
            val (driver, db) = inMemoryDb()
            try {
                val repo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) })
                val result = repo.subscribeFeed("https://ex.com/feed")
                assertIs<Result.Ok<Feeds>>(result)
                val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
                assertEquals(feed, result.value)
                return feed.id
            } finally {
                driver.close()
            }
        }

        assertEquals(feedIdForSameUrl(), feedIdForSameUrl())
    }

    @Test
    fun subscribeFeedFetchErrorInsertsNothing(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond("", HttpStatusCode.Gone) }
            var syncCount = 0
            val repo = newRepo(db, driver, fetcher, syncScheduler = { syncCount++ })

            val result = repo.subscribeFeed("https://ex.com/feed")

            assertIs<Result.Err>(result)
            assertTrue(db.feedsQueries.getAllIncludingDeleted().executeAsList().isEmpty())
            assertTrue(db.articlesQueries.watchAll().executeAsList().isEmpty())
            assertEquals(0, syncCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun subscribeFeedMakesNewArticleImmediatelySearchableWithoutManualRebuild(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS_WITH_SEARCHABLE_ARTICLE, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)

            val result = repo.subscribeFeed("https://ex.com/feed")
            assertIs<Result.Ok<Feeds>>(result)
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            assertEquals(feed, result.value)
            val article = db.articlesQueries.watchAll().executeAsList().single()

            // Regression test: before the fix, the FTS index was never rebuilt after
            // subscribeFeed(), so this search would return empty even though the article exists.
            val searchRepo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L }, Dispatchers.Unconfined)
            val results = searchRepo.search("Kotlin")

            assertEquals(listOf(article.id), results.map { it.article.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun unsubscribeThenResubscribeReusesRowInsteadOfDuplicating(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)

            val result = repo.subscribeFeed("https://ex.com/feed")
            assertIs<Result.Ok<Feeds>>(result)
            val original = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            assertEquals(original, result.value)

            repo.unsubscribeFeed(original.id)
            val afterUnsubscribe = db.feedsQueries.getById(original.id).executeAsOne()
            assertNotNull(afterUnsubscribe.deleted_at)
            assertTrue(db.feedsQueries.watchAll().executeAsList().isEmpty())

            val result2 = repo.subscribeFeed("https://ex.com/feed")
            assertIs<Result.Ok<Feeds>>(result2)

            val all = db.feedsQueries.getAllIncludingDeleted().executeAsList()
            assertEquals(1, all.size, "resubscribing to the same URL must not create a duplicate row")
            val resubscribed = all.single()
            assertEquals(resubscribed, result2.value)
            assertEquals(original.id, resubscribed.id)
            assertNull(resubscribed.deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun renameFeedWithBlankTitleClearsCustomTitle(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            repo.renameFeed(feed.id, "My custom name")
            assertEquals("My custom name", db.feedsQueries.getById(feed.id).executeAsOne().custom_title)

            repo.renameFeed(feed.id, "   ")
            assertNull(db.feedsQueries.getById(feed.id).executeAsOne().custom_title)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedHappyPathUpdatesCacheHeadersAndUpsertsArticles(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            val refreshFetcher = fetcherWith {
                respond(RSS, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "etag-2"))
            }
            val refreshRepo = newRepo(db, driver, refreshFetcher)
            val result = refreshRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Ok<Int>>(result)
            val updated = db.feedsQueries.getById(feed.id).executeAsOne()
            assertEquals("etag-2", updated.etag)
        } finally {
            driver.close()
        }
    }

    /**
     * The article-list query joins `feeds`, so SQLDelight re-runs it on every `feeds` write. A
     * steady-state refresh — same etag, same title/description, no error to clear — must therefore
     * not write the row at all; it used to issue three unconditional UPDATEs per feed, and
     * `refreshAll` multiplies that by the subscription count.
     */
    @Test
    fun refreshFeedWritesNothingWhenTheFetchChangesNoFeedColumn(): Unit = runBlocking {
        val (rawDriver, _) = inMemoryDb()
        val driver = CountingSqlDriver(rawDriver)
        val db = works.merc.keryx.app.data.local.db.KeryxDatabase(driver)
        try {
            val headers = headersOf(HttpHeaders.ETag, "etag-1")
            newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK, headers) })
                .subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            // A first refresh settles any column the subscribe path left unset (e.g. site_url).
            newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK, headers) })
                .refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            val before = driver.feedUpdates
            newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK, headers) })
                .refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertEquals(before, driver.feedUpdates, "an unchanged refresh must not write `feeds`")
        } finally {
            driver.close()
        }
    }

    /**
     * A 304 means "your validators are still current", but the fetcher can only answer it with an
     * otherwise-empty [works.merc.keryx.app.data.remote.FetchedFeed]. Writing that back used to
     * NULL out `etag` / `last_modified`, so the *next* refresh sent no `If-None-Match` and the
     * server had to return the whole feed — the conditional-request mechanism defeated itself on
     * every other poll.
     */
    @Test
    fun refreshFeedKeepsStoredValidatorsWhenTheServerAnswers304(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val etag = headersOf(HttpHeaders.ETag, "etag-1")
            newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK, etag) })
                .subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            assertEquals("etag-1", feed.etag)

            newRepo(db, driver, fetcherWith { respond("", HttpStatusCode.NotModified) })
                .refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertEquals("etag-1", db.feedsQueries.getById(feed.id).executeAsOne().etag)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedMakesNewArticleImmediatelySearchableWithoutManualRebuild(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            val refreshFetcher = fetcherWith { respond(RSS_WITH_NEW_SEARCHABLE_ARTICLE, HttpStatusCode.OK) }
            val refreshRepo = newRepo(db, driver, refreshFetcher)
            val result = refreshRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Ok<Int>>(result)
            assertEquals(1, result.value, "only the new guid (g2) should count as new")
            val newArticle = db.articlesQueries.getByFeedAndGuid(feed.id, "g2").executeAsOne()

            // Regression test: before the fix, refreshFeed() never rebuilt the FTS index, so this
            // search would return empty even though the new article exists.
            val searchRepo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L }, Dispatchers.Unconfined)
            val results = searchRepo.search("Serialization")

            assertEquals(listOf(newArticle.id), results.map { it.article.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedNoChangeDoesNotRegressExistingSearchResults(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS_WITH_SEARCHABLE_ARTICLE, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            val article = db.articlesQueries.watchAll().executeAsList().single()

            val searchRepo = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, Clock { 1000L }, Dispatchers.Unconfined)
            assertEquals(listOf(article.id), searchRepo.search("Kotlin").map { it.article.id }, "sanity check before the no-op refresh")

            // A 304-style response: no articles at all, so refreshFeedArticles.hadArticles is false
            // and the (already up-to-date) FTS index should not need — and must not be broken by —
            // the no-op refresh.
            val noChangeFetcher = fetcherWith { respond("", HttpStatusCode.NotModified) }
            val noChangeRepo = newRepo(db, driver, noChangeFetcher)
            val result = noChangeRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Ok<Int>>(result)
            assertEquals(0, result.value)
            assertEquals(listOf(article.id), searchRepo.search("Kotlin").map { it.article.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedGoneNotifiesButDoesNotIncrementErrorCount(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            val goneFetcher = fetcherWith { respond("", HttpStatusCode.Gone) }
            val notificationCenter = NotificationCenter()
            val goneRepo = newRepo(db, driver, goneFetcher, notificationCenter = notificationCenter)

            val result = goneRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Err>(result)
            val notifications = notificationCenter.items.value
            assertEquals(1, notifications.size)
            assertTrue(notifications.single().message.startsWith("gone:"))
            // Acting on the warning jumps to the feed in the sidebar.
            assertEquals(AppNotificationAction.ShowFeedDetail(feed.id), notifications.single().action)

            val after = db.feedsQueries.getById(feed.id).executeAsOne()
            assertEquals(0, after.error_count, "FeedNotFoundException is explicitly excluded from error-count increments")
            // ...so last_error carries the Gone marker instead — the only thing that lets the feed
            // list keep flagging the feed after the notification is dismissed.
            assertEquals(FEED_ERROR_REASON_GONE, after.last_error)
        } finally {
            driver.close()
        }
    }

    @Test
    fun goneMarkerIsClearedWhenTheFeedComesBack(): Unit = runBlocking {
        // The Gone marker is a live state, not a permanent brand: a feed that starts answering again
        // must lose its feed-list flag, via the existing resetErrorCount on the success path.
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) })
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            val goneRepo = newRepo(db, driver, fetcherWith { respond("", HttpStatusCode.Gone) })
            assertIs<Result.Err>(goneRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne()))
            assertEquals(FEED_ERROR_REASON_GONE, db.feedsQueries.getById(feed.id).executeAsOne().last_error)

            val backRepo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) })
            assertIs<Result.Ok<Int>>(backRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne()))

            assertNull(db.feedsQueries.getById(feed.id).executeAsOne().last_error)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedOtherErrorIncrementsErrorCount(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            val errorFetcher = fetcherWith { respond("", HttpStatusCode.InternalServerError) }
            val errorRepo = newRepo(db, driver, errorFetcher)

            val result = errorRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Err>(result)
            val after = db.feedsQueries.getById(feed.id).executeAsOne()
            assertEquals(1, after.error_count)
            assertNotNull(after.last_error)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedTimeoutIsAlsoCountedAsAnError(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()

            val timeoutClient = HttpClient(MockEngine { throw io.ktor.client.plugins.HttpRequestTimeoutException(it) }) {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout)
            }
            val timeoutFetcher = FeedFetcher(timeoutClient)
            val timeoutRepo = newRepo(db, driver, timeoutFetcher)

            val result = timeoutRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Err>(result)
            assertIs<FeedTimeoutException>(result.exception)
            val after = db.feedsQueries.getById(feed.id).executeAsOne()
            assertEquals(1, after.error_count)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedRedirectUpdatesUrlAndNotifies(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/old")
            val feed = db.feedsQueries.getByUrl("https://ex.com/old").executeAsOne()

            val redirectFetcher = fetcherWith { request ->
                if (request.url.toString().endsWith("/old")) {
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://ex.com/new"))
                } else {
                    respond(RSS, HttpStatusCode.OK)
                }
            }
            val notificationCenter = NotificationCenter()
            val redirectRepo = newRepo(db, driver, redirectFetcher, notificationCenter = notificationCenter)

            val result = redirectRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Ok<Int>>(result)
            val updated = db.feedsQueries.getById(feed.id).executeAsOne()
            assertEquals("https://ex.com/new", updated.url)
            val notifications = notificationCenter.items.value
            assertEquals(1, notifications.size)
            assertTrue(notifications.single().message.startsWith("urlChanged:"))
            assertEquals(AppNotificationAction.ShowFeedDetail(feed.id), notifications.single().action)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshAllAggregatesPerFeedResults(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed1")
            repo.subscribeFeed("https://ex.com/feed2")

            val mixedFetcher = fetcherWith { request ->
                if (request.url.toString().endsWith("/feed1")) {
                    respond(RSS, HttpStatusCode.OK)
                } else {
                    respond("", HttpStatusCode.Gone)
                }
            }
            val mixedRepo = newRepo(db, driver, mixedFetcher)

            val results = mixedRepo.refreshAll()

            assertEquals(2, results.size)
            val byUrl = results.entries.associate { (id, r) -> db.feedsQueries.getById(id).executeAsOne().url to r }
            assertIs<Result.Ok<Int>>(byUrl.getValue("https://ex.com/feed1"))
            assertIs<Result.Err>(byUrl.getValue("https://ex.com/feed2"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshAllFetchesFeedsConcurrentlyAndAppliesEveryWrite(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val n = 6 // stays within the internal fetch-concurrency bound so all can overlap
            val setupRepo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) })
            for (i in 1..n) setupRepo.subscribeFeed("https://ex.com/feed$i")

            // Each fetch records how many were in flight simultaneously (proving phase 1 runs
            // concurrently, not sequentially) and returns a NEW article (g2) for its feed.
            val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
            val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
            val concurrentFetcher = fetcherWith {
                val cur = inFlight.incrementAndGet()
                maxInFlight.getAndUpdate { m -> if (cur > m) cur else m }
                delay(30) // hold the fetch open so sibling fetches overlap
                inFlight.decrementAndGet()
                respond(RSS_WITH_NEW_SEARCHABLE_ARTICLE, HttpStatusCode.OK)
            }
            val repo = newRepo(db, driver, concurrentFetcher)

            val results = withTimeout(10_000) { repo.refreshAll() }

            assertEquals(n, results.size)
            assertTrue(results.values.all { it is Result.Ok }, "all feeds should refresh Ok: $results")
            assertTrue(maxInFlight.get() >= 2, "fetches did not overlap; max in-flight=${maxInFlight.get()}")
            // Every feed's serially-applied write landed: each feed now holds its 2nd article (g2) too,
            // so nothing was lost when the concurrent fetches funneled into the sequential write phase.
            assertEquals(2 * n, db.articlesQueries.watchAll().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshAllDoesNotRevertConcurrentUnsubscribe(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val url = "https://ex.com/feed"
            newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) }).subscribeFeed(url)
            val id = db.feedsQueries.getByUrl(url).executeAsOne().id

            // Simulate the user unsubscribing this feed *during* refreshAll's concurrent fetch phase,
            // before any DB write is applied. Deterministic: phase 1 (all fetches) fully completes
            // before phase 2 (serial applies) begins, so the soft-delete is already committed when
            // applyFetch runs against the pre-unsubscribe snapshot.
            val fetcher = fetcherWith {
                db.feedsQueries.softDelete(1L, 1L, 1L, id)
                respond(RSS_WITH_NEW_SEARCHABLE_ARTICLE, HttpStatusCode.OK)
            }
            val results = newRepo(db, driver, fetcher).refreshAll()
            assertIs<Result.Ok<Int>>(results.getValue(id))

            // The refresh must not resurrect the just-unsubscribed feed.
            assertNotNull(db.feedsQueries.getById(id).executeAsOneOrNull()?.deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshAllDoesNotRevertConcurrentReorder(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val url = "https://ex.com/feed"
            newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) }).subscribeFeed(url)
            val id = db.feedsQueries.getByUrl(url).executeAsOne().id

            // Simulate the user reordering this feed during the concurrent fetch phase (see above).
            val fetcher = fetcherWith {
                db.feedsQueries.updateSortOrder(99L, 1L, 1L, id)
                respond(RSS_WITH_NEW_SEARCHABLE_ARTICLE, HttpStatusCode.OK)
            }
            val results = newRepo(db, driver, fetcher).refreshAll()
            assertIs<Result.Ok<Int>>(results.getValue(id))

            // The refresh must not revert the concurrent reorder.
            assertEquals(99L, db.feedsQueries.getById(id).executeAsOne().sort_order)
        } finally {
            driver.close()
        }
    }

    @Test
    fun moveFeedSetsFolderIdAndSchedulesSync(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            var syncCount = 0
            val repo = newRepo(db, driver, fetcher, syncScheduler = { syncCount++ })
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            syncCount = 0

            repo.moveFeed(feed.id, "d1")

            assertEquals("d1", db.feedsQueries.getById(feed.id).executeAsOne().folder_id)
            assertEquals(1, syncCount)

            repo.moveFeed(feed.id, null)

            assertNull(db.feedsQueries.getById(feed.id).executeAsOne().folder_id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun refreshFeedUpsertNeverTouchesFolderIdOfExistingFeed(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 10L)
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            repo.moveFeed(feed.id, "d1")
            assertEquals("d1", db.feedsQueries.getById(feed.id).executeAsOne().folder_id)

            // Drive the feed through the upsert-based refresh path (title/description present
            // triggers `feeds.upsert`), and confirm the existing folder_id survives untouched.
            val refreshFetcher = fetcherWith {
                respond(RSS, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "etag-refreshed"))
            }
            val refreshRepo = newRepo(db, driver, refreshFetcher)
            val result = refreshRepo.refreshFeed(db.feedsQueries.getById(feed.id).executeAsOne())

            assertIs<Result.Ok<Int>>(result)
            assertEquals("d1", db.feedsQueries.getById(feed.id).executeAsOne().folder_id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun moveFeedReordersWithinTheSameGroupWithoutTouchingUpdatedAtOfUnaffectedFeeds(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1", now = 0L, sortOrder = 0L)
            db.insertFeed("f2", now = 0L, sortOrder = 1L)
            db.insertFeed("f3", now = 0L, sortOrder = 2L)
            db.insertFeed("f4", now = 0L, sortOrder = 3L)
            var syncCount = 0
            val repo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) }, syncScheduler = { syncCount++ }, clock = Clock { 999L })

            // Move f4 to be right before f2: new order is f1, f4, f2, f3.
            repo.moveFeed("f4", folderId = null, targetFeedId = "f2")

            val ordered = db.feedsQueries.getByFolder(null).executeAsList()
            assertEquals(listOf("f1", "f4", "f2", "f3"), ordered.map { it.id })
            assertEquals(1, syncCount)

            // f1 keeps index 0 (unchanged), so it must be left completely untouched — this is the
            // "don't bump unrelated updated_at" guarantee moveFeed exists to preserve. f2 and f3
            // both shift down by one index, so they (like the dragged f4 itself) do get rewritten.
            assertEquals(0L, db.feedsQueries.getById("f1").executeAsOne().updated_at)
            assertEquals(999L, db.feedsQueries.getById("f4").executeAsOne().updated_at)
            assertEquals(999L, db.feedsQueries.getById("f2").executeAsOne().updated_at)
            assertEquals(999L, db.feedsQueries.getById("f3").executeAsOne().updated_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun moveFeedAcrossFoldersPositionsWithinTheDestinationGroup(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 0L)
            db.insertFeed("f1", now = 0L, folderId = "d1", sortOrder = 0L)
            db.insertFeed("f2", now = 0L, folderId = "d1", sortOrder = 1L)
            db.insertFeed("moving", now = 0L, sortOrder = 0L)
            val repo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) }, clock = Clock { 500L })

            repo.moveFeed("moving", folderId = "d1", targetFeedId = "f2")

            val ordered = db.feedsQueries.getByFolder("d1").executeAsList()
            assertEquals(listOf("f1", "moving", "f2"), ordered.map { it.id })
            assertEquals("d1", db.feedsQueries.getById("moving").executeAsOne().folder_id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getFeedByUrlReturnsTheMatchingFeedIncludingASoftDeletedOneAndNullOtherwise() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1", url = "https://a.com/feed")
            db.insertFeed("f2", url = "https://b.com/feed", deletedAt = 20L)
            val repo = newRepo(db, driver, fetcherWith { respond(RSS, HttpStatusCode.OK) })

            assertEquals("f1", repo.getFeedByUrl("https://a.com/feed")?.id)
            // getByUrl deliberately ignores deleted_at — importOpml resolves unsubscribed feeds too.
            assertNotNull(repo.getFeedByUrl("https://b.com/feed")?.deleted_at)
            assertNull(repo.getFeedByUrl("https://missing.com/feed"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun subscribeFeedNewFeedIsAppendedToEndOfNoFolderGroup(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("existing", now = 0L, sortOrder = 0L)
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)

            repo.subscribeFeed("https://ex.com/feed")

            val newFeed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            assertEquals(1L, newFeed.sort_order)
        } finally {
            driver.close()
        }
    }

    @Test
    fun subscribeFeedResubscribingAfterUnsubscribeIsRenumberedToEndOfItsOldGroup(): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFolder("d1", "Kotlin", now = 0L)
            val fetcher = fetcherWith { respond(RSS, HttpStatusCode.OK) }
            val repo = newRepo(db, driver, fetcher)
            repo.subscribeFeed("https://ex.com/feed")
            val feed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            repo.moveFeed(feed.id, "d1")
            repo.unsubscribeFeed(feed.id)

            // While unsubscribed, another feed joins "d1" ahead of where the old sort_order (0)
            // would have placed it.
            db.insertFeed("other", now = 0L, folderId = "d1", sortOrder = 0L)

            repo.subscribeFeed("https://ex.com/feed")

            val resubscribed = db.feedsQueries.getByUrl("https://ex.com/feed").executeAsOne()
            assertNull(resubscribed.deleted_at)
            assertEquals("d1", resubscribed.folder_id)
            val ordered = db.feedsQueries.getByFolder("d1").executeAsList()
            assertEquals(listOf("other", feed.id), ordered.map { it.id })
        } finally {
            driver.close()
        }
    }
}
