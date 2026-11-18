package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NextcloudServerProblem
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NextcloudServerProbeTest {

    private val history = mutableListOf<HttpRequestData>()

    private fun probe(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NextcloudServerProbe {
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            handler(request)
        }) {
            expectSuccess = false
            followRedirects = false
        }
        return NextcloudServerProbe(client)
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun anInstalledServerPasses() = runTest {
        val p = probe { respond("""{"installed":true,"maintenance":false,"version":"32.0.1.2"}""", HttpStatusCode.OK, jsonHeaders) }
        assertIs<Result.Ok<Unit>>(p.probe("https://cloud.example.com/nextcloud"))
        assertEquals("https://cloud.example.com/nextcloud/status.php", history.single().url.toString())
        assertEquals("GET", history.single().method.value)
        assertTrue(history.single().headers[HttpHeaders.UserAgent].orEmpty().isNotEmpty())
    }

    @Test
    fun aServerThatIsNotInstalledIsNotNextcloud() = runTest {
        val p = probe { respond("""{"installed":false}""", HttpStatusCode.OK, jsonHeaders) }
        assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, p.probe("https://cloud.example.com"))
    }

    @Test
    fun anHtmlPageIsNotNextcloud() = runTest {
        val p = probe { respond("<html><body>Hello</body></html>", HttpStatusCode.OK) }
        assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, p.probe("https://example.com"))
    }

    @Test
    fun aNonObjectInstalledFieldIsNotNextcloud() = runTest {
        val p = probe { respond("""{"installed":{"value":true}}""", HttpStatusCode.OK, jsonHeaders) }
        assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, p.probe("https://example.com"))
    }

    @Test
    fun anUnparsableContentTypeIsNotNextcloud() = runTest {
        // bodyAsText() parses Content-Type after the request has returned; a malformed one throws there.
        val p = probe { respond("""{"installed":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "invalid")) }
        assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, p.probe("https://example.com"))
    }

    @Test
    fun a404IsNotNextcloud() = runTest {
        val p = probe { respond("not found", HttpStatusCode.NotFound) }
        assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, p.probe("https://example.com"))
    }

    @Test
    fun aRedirectIsNotFollowedAndCountsAsNotNextcloud() = runTest {
        val p = probe {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "http://example.com/status.php"))
        }
        assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, p.probe("https://example.com"))
        assertEquals(1, history.size)
    }

    @Test
    fun noResponseIsUnreachable() = runTest {
        val p = probe { throw IOException("connection refused") }
        assertProblem(NextcloudServerProblem.UNREACHABLE, p.probe("https://example.com"))
    }

    @Test
    fun aNonHttpsBaseIsRejectedWithoutARequest() = runTest {
        val p = probe { respond("""{"installed":true}""", HttpStatusCode.OK, jsonHeaders) }
        assertProblem(NextcloudServerProblem.HTTPS_REQUIRED, p.probe("http://example.com"))
        assertTrue(history.isEmpty())
    }

    private fun assertProblem(expected: NextcloudServerProblem, result: Result<Unit>) {
        assertIs<Result.Err>(result)
        assertEquals(expected, assertIs<NextcloudServerException>(result.exception).problem)
    }
}
