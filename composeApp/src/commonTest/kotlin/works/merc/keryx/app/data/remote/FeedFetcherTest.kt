package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.FEED_TIMEOUT_RETRY_COUNT
import works.merc.keryx.app.core.FeedDiscoveryException
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.FeedParseException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.MAX_REDIRECTS
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

class FeedFetcherTest {

    private fun fetcherWith(handler: MockRequestHandler): FeedFetcher {
        val client = HttpClient(MockEngine(handler)) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    @Test
    fun fetchesAndParsesRss() = runBlocking {
        val f = fetcherWith { respond(RSS, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "etag-1")) }
        val r = f.fetch("https://ex.com/feed")
        assertIs<Result.Ok<FetchedFeed>>(r)
        assertEquals("Feed", r.value.title)
        assertEquals("etag-1", r.value.etag)
        assertEquals(1, r.value.articles.size)
    }

    @Test
    fun notModifiedReturnsEmpty() = runBlocking {
        val f = fetcherWith { respond("", HttpStatusCode.NotModified) }
        val r = f.fetch("https://ex.com/feed", etag = "etag-1")
        assertIs<Result.Ok<FetchedFeed>>(r)
        assertTrue(r.value.articles.isEmpty())
    }

    @Test
    fun followsPermanentRedirectAndReportsNewUrl() = runBlocking {
        val f = fetcherWith { request ->
            if (request.url.toString().endsWith("/old")) {
                respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://ex.com/new"))
            } else {
                respond(RSS, HttpStatusCode.OK)
            }
        }
        val r = f.fetch("https://ex.com/old")
        assertIs<Result.Ok<FetchedFeed>>(r)
        assertEquals("https://ex.com/new", r.value.redirectUrl)
    }

    @Test
    fun goneReturnsFeedNotFound() = runBlocking {
        val f = fetcherWith { respond("", HttpStatusCode.Gone) }
        val r = f.fetch("https://ex.com/feed")
        assertIs<Result.Err>(r)
        val ex = r.exception
        assertIs<FeedNotFoundException>(ex)
        assertTrue(ex.isGone)
    }

    @Test
    fun notFoundReturnsFeedNotFoundWithoutGone() = runBlocking {
        val f = fetcherWith { respond("", HttpStatusCode.NotFound) }
        val r = f.fetch("https://ex.com/feed")
        assertIs<Result.Err>(r)
        val ex = r.exception
        assertIs<FeedNotFoundException>(ex)
        assertTrue(!ex.isGone)
    }

    @Test
    fun followsTemporaryRedirectsWithoutUpdatingUrl() = runBlocking {
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect)) {
            val f = fetcherWith { request ->
                if (request.url.toString().endsWith("/old")) {
                    respond("", status, headersOf(HttpHeaders.Location, "https://ex.com/new"))
                } else {
                    respond(RSS, HttpStatusCode.OK)
                }
            }
            val r = f.fetch("https://ex.com/old")
            assertIs<Result.Ok<FetchedFeed>>(r)
            assertNull(r.value.redirectUrl)
        }
    }

    @Test
    fun redirectWithoutLocationHeaderFails() = runBlocking {
        val f = fetcherWith { respond("", HttpStatusCode.Found) }
        val r = f.fetch("https://ex.com/feed")
        assertIs<Result.Err>(r)
        val ex = r.exception
        assertIs<FeedFetchException>(ex)
        assertEquals("302 without Location header", ex.message)
    }

    @Test
    fun redirectLoopGuardStopsAfterMaxRedirects() = runBlocking {
        var attempts = 0
        val f = fetcherWith { request ->
            attempts++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://ex.com/loop"))
        }
        val r = f.fetch("https://ex.com/loop")
        assertIs<Result.Err>(r)
        val ex = r.exception
        assertIs<FeedFetchException>(ex)
        assertEquals("Too many redirects", ex.message)
        assertEquals(MAX_REDIRECTS + 1, attempts)
    }

    @Test
    fun timeoutExhaustsRetriesThenFails() = runBlocking {
        var attempts = 0
        val f = fetcherWith { request ->
            attempts++
            throw HttpRequestTimeoutException(request)
        }
        val r = f.fetch("https://ex.com/feed")
        assertIs<Result.Err>(r)
        assertIs<FeedTimeoutException>(r.exception)
        assertEquals(FEED_TIMEOUT_RETRY_COUNT + 1, attempts)
    }

    @Test
    fun unknownFormatWithoutDiscoverableLinksFails() = runBlocking {
        val f = fetcherWith { respond("<html><body>no feed here</body></html>", HttpStatusCode.OK) }
        val r = f.fetch("https://ex.com/")
        assertIs<Result.Err>(r)
        assertIs<FeedParseException>(r.exception)
        Unit
    }

    @Test
    fun cancellationPropagatesNotConvertedToError() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val f = fetcherWith {
            started.complete(Unit)
            gate.await()
            respond(RSS, HttpStatusCode.OK)
        }
        var result: Result<FetchedFeed>? = null
        val job = launch(Dispatchers.Default) { result = f.fetch("https://ex.com/feed") }
        started.await()
        // Real (non-virtual) delay: give the fetch coroutine a moment to actually suspend on the
        // gate before we cancel, so this exercises genuine coroutine cancellation rather than a
        // race where cancel() lands before the HTTP call is even in flight.
        delay(50)
        job.cancel()
        job.join()
        assertNull(result)
    }

    @Test
    fun htmlPageWithFeedLinkTriggersDiscovery() = runBlocking {
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" href="/feed.xml" title="RSS"/>
            </head><body>site</body></html>"""
        val f = fetcherWith { respond(html, HttpStatusCode.OK) }
        val r = f.fetch("https://ex.com/")
        assertIs<Result.Err>(r)
        val ex = r.exception
        assertIs<FeedDiscoveryException>(ex)
        assertEquals("https://ex.com/feed.xml", ex.candidates.single().url)
    }
}
