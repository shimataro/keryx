package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.Log
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
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
    fun `a malformed escape is rejected without completing and never logs the secret`() {
        var code = 0
        val records = withCapturedLogRecords {
            val params = runCapture(timeoutMillis = 1_000) { uri ->
                code = get("$uri/?state=s&code=SECRET%zz").code
            }
            assertNull(params, "a malformed callback never completes the wait")
        }

        // The JDK HttpServer itself rejects an unparseable request URI with 400 before the handler's own
        // catch (which answers 500 after logging a warning) can run; either way the callback is rejected.
        assertTrue(code == 400 || code == 500, "rejected, was $code")
        records.forEach { record ->
            assertFalse(record.message.orEmpty().contains("SECRET"), record.message)
            generateSequence(record.thrown) { it.cause }.forEach {
                assertFalse(it.message.orEmpty().contains("SECRET"), it.message)
                assertFalse(it.toString().contains("SECRET"), it.toString())
            }
        }
    }

    /** Same white-box capture pattern as `SingleInstanceCoordinatorTest`: attach a handler to the JUL logger [Log] writes to. */
    private fun withCapturedLogRecords(block: () -> Unit): List<LogRecord> {
        val previousLogDir = System.getProperty("keryx.log.dir")
        System.setProperty("keryx.log.dir", System.getProperty("java.io.tmpdir"))
        val logger = Logger.getLogger(Log.LOGGER_NAME)
        val captured = java.util.concurrent.CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            if (previousLogDir == null) {
                System.clearProperty("keryx.log.dir")
            } else {
                System.setProperty("keryx.log.dir", previousLogDir)
            }
        }
        return captured.toList()
    }
}
