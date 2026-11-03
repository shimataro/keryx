package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives [LoopbackRedirectTransport] with real HTTP requests to the redirect URI its `launchBrowser` receives. */
class LoopbackRedirectTransportTest {

    private class Response(val code: Int, val body: String)

    /** One blocking GET against the (possibly deliberately malformed) [uri]. */
    @Suppress("DEPRECATION")
    private suspend fun get(uri: String): Response = withContext(Dispatchers.IO) {
        // java.net.URL (unlike URI) does not validate the escapes, which the malformed-escape test needs.
        val connection = URL(uri).openConnection() as HttpURLConnection
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Response(code, stream?.readBytes()?.decodeToString().orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Runs `capture` and hands [interact] the redirect URI once the browser is "launched"; returns what
     * `capture` returned.
     */
    private fun runCapture(
        transport: LoopbackRedirectTransport = LoopbackRedirectTransport { "Done" },
        state: String = "s",
        timeoutMillis: Long = 10_000,
        interact: suspend (redirectUri: String) -> Unit,
    ): OAuthCallbackParams? = runBlocking {
        val uri = CompletableDeferred<String>()
        val result = async(Dispatchers.IO) {
            transport.capture(state, timeoutMillis) { uri.complete(it) }
        }
        // If capture fails before it launches the browser (a bind failure, say), the redirect URI is never
        // produced; fail the wait with that error instead of hanging the whole test run on it.
        result.invokeOnCompletion { cause -> if (cause != null) uri.completeExceptionally(cause) }
        interact(uri.await())
        result.await()
    }

    @Test
    fun `matching state and code returns the params and serves an HTML page`() {
        var response: Response = Response(0, "")
        val params = runCapture(LoopbackRedirectTransport { "All done" }) { uri ->
            response = get("$uri/?state=s&code=abc")
        }

        assertEquals(OAuthCallbackParams(code = "abc", state = "s", error = null, errorDescription = null), params)
        assertEquals(200, response.code)
        assertTrue(response.body.contains("<p>All done</p>"))
        assertTrue(response.body.startsWith("<!doctype html>"))
    }

    @Test
    fun `an error callback with the matching state also completes the wait`() {
        val params = runCapture { uri -> get("$uri/?state=s&error=access_denied&error_description=No+thanks") }

        assertEquals("access_denied", params?.error)
        assertEquals("No thanks", params?.errorDescription)
        assertNull(params?.code)
    }

    @Test
    fun `a wrong state and a request without code are 404 and the wait continues until a valid callback`() {
        val codes = mutableListOf<Int>()
        val params = runCapture { uri ->
            codes += get("$uri/?state=other&code=evil").code
            codes += get("$uri/favicon.ico").code
            codes += get("$uri/?state=s").code
            codes += get("$uri/?state=s&code=good").code
        }

        assertEquals(listOf(404, 404, 404, 200), codes)
        assertEquals("good", params?.code)
    }

    @Test
    fun `the success message is HTML-escaped`() {
        var body = ""
        runCapture(LoopbackRedirectTransport { "<b>&</b>" }) { uri -> body = get("$uri/?state=s&code=c").body }

        assertTrue(body.contains("<p>&lt;b&gt;&amp;&lt;/b&gt;</p>"), body)
        assertFalse(body.contains("<b>"))
    }

    @Test
    fun `a timeout returns null and stops the server`() {
        var redirectUri = ""
        val params = runCapture(timeoutMillis = 200) { uri -> redirectUri = uri }

        assertNull(params)
        assertFailsWith<ConnectException> { runBlocking { get("$redirectUri/?state=s&code=late") } }
    }

    @Test
    fun `a malformed escape is rejected without completing the wait`() {
        var code = 0
        val params = runCapture(timeoutMillis = 1_000) { uri ->
            code = get("$uri/?state=s&code=SECRET%zz").code
        }

        // The JDK HttpServer itself rejects an unparseable request URI with 400 before the handler runs
        // (so the handler's own catch, which would answer 500, is out of reach from here). Either way the
        // callback is rejected and never completes the wait. That the parser's error never quotes a value
        // is pinned where it can actually be reached: OAuthUriParserTest.
        assertTrue(code == 400 || code == 500, "rejected, was $code")
        assertNull(params, "a malformed callback never completes the wait")
    }

    @Test
    fun `the callback is reachable at 127_0_0_1 even when the JVM prefers IPv6`() {
        // java.net.preferIPv6Addresses is read at JVM startup, so run the capture in a child JVM with it set:
        // InetAddress.getLoopbackAddress() is then ::1, and a server bound to it refuses the 127.0.0.1 redirect.
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(
            java,
            "-Djava.net.preferIPv6Addresses=true",
            "-cp", System.getProperty("java.class.path"),
            LoopbackChildJvm::class.java.name,
        ).redirectErrorStream(true).start()

        val finished = child.waitFor(30, TimeUnit.SECONDS)
        val output = if (finished) child.inputStream.readBytes().decodeToString() else ""
        if (!finished) child.destroyForcibly()

        assertTrue(finished, "the child JVM did not finish in time")
        assertEquals(0, child.exitValue(), output)
        assertTrue(output.contains("code=${LoopbackChildJvm.CODE}"), output)
    }
}
