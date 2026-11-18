package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Direct tests of [ReleaseFeedSource]'s list endpoint; `UpdateCheckerTest` covers `releases/latest` through the checker. */
class ReleaseFeedSourceTest {

    private val listJson = """
        [
          {"tag_name":"v0.9.0","html_url":"https://ex.com/0.9.0","prerelease":true,"draft":false,"body":"notes",
           "assets":[
             {"name":"a.dmg","browser_download_url":"https://ex.com/a.dmg","size":42,"digest":"sha256:abc","state":"uploaded"},
             {"name":"no-size.dmg","browser_download_url":"https://ex.com/b.dmg"},
             {"name":"b.msi","browser_download_url":"https://ex.com/b.msi","size":7}
           ]},
          {"tag_name":"v0.8.0","draft":true},
          "not-an-object"
        ]
    """.trimIndent()

    private fun source(history: MutableList<HttpRequestData> = mutableListOf(), respondWith: MockRequestHandler): ReleaseFeedSource {
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            respondWith(request)
        }) { expectSuccess = false }
        return ReleaseFeedSource(client, "0.5.0")
    }

    @Test
    fun parsesReleaseFieldsAndAssets() = runBlocking {
        val r = source { respond(listJson) }.fetchReleaseList("o/r")
        assertIs<Result.Ok<List<ReleaseInfo>>>(r)
        assertEquals(2, r.value.size, "a non-object entry is skipped")
        val first = r.value[0]
        assertEquals("v0.9.0", first.tagName)
        assertEquals("https://ex.com/0.9.0", first.htmlUrl)
        assertTrue(first.prerelease)
        assertEquals(false, first.draft)
        assertEquals("notes", first.body)
        assertEquals(
            listOf(
                ReleaseAsset("a.dmg", "https://ex.com/a.dmg", 42, "sha256:abc", "uploaded"),
                ReleaseAsset("b.msi", "https://ex.com/b.msi", 7, null, null),
            ),
            first.assets,
        )
        val second = r.value[1]
        assertTrue(second.draft)
        assertNull(second.htmlUrl)
        assertNull(second.body)
        assertTrue(second.assets.isEmpty())
    }

    @Test
    fun sendsPerPageUserAgentAndAcceptHeaders() = runBlocking {
        val history = mutableListOf<HttpRequestData>()
        source(history) { respond("[]") }.fetchReleaseList("o/r")
        val req = history.single()
        assertEquals("https://api.github.com/repos/o/r/releases?per_page=100", req.url.toString())
        assertEquals("Keryx/0.5.0", req.headers[HttpHeaders.UserAgent])
        assertEquals("application/vnd.github+json", req.headers[HttpHeaders.Accept])
        assertNull(req.headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun secondFetchSendsIfNoneMatchAndReplaysTheCachedListOn304() = runBlocking {
        val history = mutableListOf<HttpRequestData>()
        val src = source(history) {
            if (history.size == 1) respond(listJson, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v1\""))
            else respond("", HttpStatusCode.NotModified)
        }
        val first = src.fetchReleaseList("o/r")
        val second = src.fetchReleaseList("o/r")
        assertEquals("\"v1\"", history[1].headers[HttpHeaders.IfNoneMatch])
        assertIs<Result.Ok<List<ReleaseInfo>>>(first)
        assertEquals(first, second)
    }

    @Test
    fun a304WithoutACachedListIsAnHttpError() = runBlocking {
        val r = source { respond("", HttpStatusCode.NotModified) }.fetchReleaseList("o/r")
        assertIs<Result.Err>(r)
        val e = assertIs<UpdateException>(r.exception)
        assertEquals(UpdateStage.CHECK, e.stage)
        assertEquals("HTTP 304", e.message)
    }

    @Test
    fun latestReturnsEmptyOn404AndErrorsOnOtherStatuses() = runBlocking {
        assertEquals(Result.Ok(emptyList()), source { respond("", HttpStatusCode.NotFound) }.fetchLatestRelease("o/r"))
        val r = source { respond("", HttpStatusCode.InternalServerError) }.fetchLatestRelease("o/r")
        assertIs<Result.Err>(r)
        assertEquals("HTTP 500", assertIs<UpdateException>(r.exception).message)
    }

    @Test
    fun listServerErrorAndUnexpectedBodyAreCheckFailures() = runBlocking {
        val err = source { respond("", HttpStatusCode.Forbidden) }.fetchReleaseList("o/r")
        assertEquals("HTTP 403", assertIs<UpdateException>(assertIs<Result.Err>(err).exception).message)

        val notArray = source { respond("""{"a":1}""") }.fetchReleaseList("o/r")
        assertEquals("Unexpected response body", assertIs<UpdateException>(assertIs<Result.Err>(notArray).exception).message)

        val latestNotObject = source { respond("[]") }.fetchLatestRelease("o/r")
        assertEquals("Unexpected response body", assertIs<UpdateException>(assertIs<Result.Err>(latestNotObject).exception).message)
    }

    @Test
    fun networkFailureBecomesACheckFailureButCancellationPropagates() = runBlocking {
        val failing = HttpClient(MockEngine { throw IllegalStateException("offline") }) { expectSuccess = false }
        val r = ReleaseFeedSource(failing, "1.0.0").fetchReleaseList("o/r")
        val e = assertIs<UpdateException>(assertIs<Result.Err>(r).exception)
        assertEquals(UpdateStage.CHECK, e.stage)
        assertEquals("offline", e.message)

        val cancelling = HttpClient(MockEngine { throw CancellationException("stop") }) { expectSuccess = false }
        assertFailsWith<CancellationException> { ReleaseFeedSource(cancelling, "1.0.0").fetchReleaseList("o/r") }
        Unit
    }
}
