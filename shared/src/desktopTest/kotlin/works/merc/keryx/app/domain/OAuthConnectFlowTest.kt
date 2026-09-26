package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OAuthConnectFlow.connect] mostly orchestrates a real system browser launch
 * ([works.merc.keryx.app.platform.BrowserOpener], an `actual` object with no seam
 * for injection) and, depending on the [OAuthRedirectTransport], either a custom-URI
 * callback or a real loopback HTTP server. It cannot be exercised end-to-end from a
 * unit test without actually opening a browser window, so only the pure, pre-flight
 * branch (missing/blank client id short-circuits before any side effect) is covered
 * here. The browser-open / callback-wait / code-exchange path is left untested at
 * this layer — including whether it logs on failure, since reaching any of those
 * branches would itself require a real browser launch as a side effect.
 */
class OAuthConnectFlowTest {

    @Test
    fun connectFailsFastWhenClientIdIsEmpty() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val authManager = DropboxAuthManager(client)
        val transport = CustomUriRedirectTransport(callbackFlow)
        val flow = OAuthConnectFlow(authManager, clientId = "", transport = transport)

        val result = flow.connect()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }

    @Test
    fun connectLogsWhenClientIdIsEmpty() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val authManager = DropboxAuthManager(client)
        val transport = CustomUriRedirectTransport(callbackFlow)
        val flow = OAuthConnectFlow(authManager, clientId = "", transport = transport)

        val records = withCapturedLogRecords { flow.connect() }

        assertTrue(records.any { it.message.contains("not configured") })
    }

    /** Same white-box capture pattern as `core.LogTest` / `SingleInstanceCoordinatorTest`. */
    private suspend fun withCapturedLogRecords(block: suspend () -> Unit): List<LogRecord> {
        val previousLogDir = System.getProperty("keryx.log.dir")
        System.setProperty("keryx.log.dir", System.getProperty("java.io.tmpdir"))
        val logger = Logger.getLogger(Log.LOGGER_NAME)
        val captured = mutableListOf<LogRecord>()
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
        return captured
    }
}
