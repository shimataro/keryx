package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FaviconResolverTest {

    private fun resolverWith(handler: MockRequestHandler): FaviconResolver {
        val client = HttpClient(MockEngine(handler)) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FaviconResolver(client)
    }

    private fun html(vararg links: String): String =
        "<html><head>${links.joinToString("")}</head><body>site</body></html>"

    @Test
    fun prefersSvgOverBitmapCandidates() = runTest {
        val page = html(
            """<link rel="icon" type="image/png" sizes="32x32" href="/icon-32.png">""",
            """<link rel="icon" type="image/svg+xml" href="/icon.svg">""",
            """<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">""",
        )
        val r = resolverWith { request ->
            when (request.method) {
                HttpMethod.Get -> respond(page, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.OK)
            }
        }
        assertEquals("https://ex.com/icon.svg", r.resolve("https://ex.com", "https://ex.com/feed"))
    }

    @Test
    fun prefersLargestBitmapWhenNoSvg() = runTest {
        val page = html(
            """<link rel="icon" sizes="16x16" href="/icon-16.png">""",
            """<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">""",
            """<link rel="icon" sizes="32x32" href="/icon-32.png">""",
        )
        val r = resolverWith { request ->
            when (request.method) {
                HttpMethod.Get -> respond(page, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.OK)
            }
        }
        assertEquals("https://ex.com/apple-touch-icon.png", r.resolve("https://ex.com", "https://ex.com/feed"))
    }

    @Test
    fun excludesMaskIconAndFallsBackToNormalIcon() = runTest {
        val page = html(
            """<link rel="mask-icon" href="/mask-icon.svg" color="#000000">""",
            """<link rel="icon" sizes="32x32" href="/icon-32.png">""",
        )
        val r = resolverWith { request ->
            when (request.method) {
                HttpMethod.Get -> respond(page, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.OK)
            }
        }
        assertEquals("https://ex.com/icon-32.png", r.resolve("https://ex.com", "https://ex.com/feed"))
    }

    @Test
    fun fallsBackToNextCandidateWhenTopChoiceUnreachable() = runTest {
        val page = html(
            """<link rel="icon" type="image/svg+xml" href="/icon.svg">""",
            """<link rel="icon" sizes="32x32" href="/icon-32.png">""",
        )
        val r = resolverWith { request ->
            when {
                request.method == HttpMethod.Get -> respond(page, HttpStatusCode.OK)
                request.url.toString().endsWith("/icon.svg") -> respond("", HttpStatusCode.NotFound)
                else -> respond("", HttpStatusCode.OK)
            }
        }
        assertEquals("https://ex.com/icon-32.png", r.resolve("https://ex.com", "https://ex.com/feed"))
    }

    @Test
    fun fallsBackToFaviconIcoWhenNoCandidateReachable() = runTest {
        val page = html("""<link rel="icon" href="/icon.png">""")
        val r = resolverWith { request ->
            when {
                request.method == HttpMethod.Get -> respond(page, HttpStatusCode.OK)
                request.url.toString().endsWith("/favicon.ico") -> respond("", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        assertEquals("https://ex.com/favicon.ico", r.resolve("https://ex.com", "https://ex.com/feed"))
    }
}
