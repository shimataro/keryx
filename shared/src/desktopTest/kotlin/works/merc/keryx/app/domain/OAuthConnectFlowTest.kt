package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OAuthConnectFlow.connect] used to only be testable up to its pre-flight branch (missing/blank
 * client id), because it opened the system browser directly with no seam for injection. It now
 * takes an [AuthorizationLauncher] instead, so [FakeAuthorizationLauncher] below drives the whole
 * flow — browser "open", callback delivery, state validation and code exchange — without any real
 * browser window. This still lives in `desktopTest` rather than `commonTest` only because
 * [withCapturedLogRecords] needs `java.util.logging`.
 */
class OAuthConnectFlowTest {

    /** Captures the URL [OAuthConnectFlow] would have opened, and lets the test deliver a callback. */
    private class FakeAuthorizationLauncher(
        private val callbackFlow: MutableSharedFlow<OAuthCallbackParams>,
        private val respondWith: (state: String) -> OAuthCallbackParams,
    ) : AuthorizationLauncher {
        var lastAuthorizeUrl: String? = null
            private set
        var lastRedirectUri: String? = null
            private set

        override fun launch(authorizeUrl: String, redirectUri: String) {
            lastAuthorizeUrl = authorizeUrl
            lastRedirectUri = redirectUri
            val state = Regex("state=([^&]*)").find(authorizeUrl)?.groupValues?.get(1)
                ?: error("authorizeUrl carried no state: $authorizeUrl")
            check(callbackFlow.tryEmit(respondWith(state)))
        }
    }

    private fun tokenClient(body: String = """{"access_token":"AT","refresh_token":"RT","expires_in":14400}""") =
        HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }) {
            expectSuccess = false
        }

    @Test
    fun connectFailsFastWhenClientIdIsEmpty() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val authManager = DropboxAuthManager(tokenClient())
        val transport = CustomUriRedirectTransport(callbackFlow)
        val flow = OAuthConnectFlow(authManager, clientId = "", transport = transport)

        val result = flow.connect()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }

    @Test
    fun connectLogsWhenClientIdIsEmpty() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val authManager = DropboxAuthManager(tokenClient())
        val transport = CustomUriRedirectTransport(callbackFlow)
        val flow = OAuthConnectFlow(authManager, clientId = "", transport = transport)

        val records = withCapturedLogRecords { flow.connect() }

        assertTrue(records.any { it.message.contains("not configured") })
    }

    @Test
    fun connectOpensBuiltAuthorizeUrlAndSucceedsOnMatchingCallback() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val launcher = FakeAuthorizationLauncher(callbackFlow) { state ->
            OAuthCallbackParams(code = "CODE", state = state, error = null, errorDescription = null)
        }
        val authManager = DropboxAuthManager(tokenClient())
        val flow = OAuthConnectFlow(
            authManager,
            clientId = "APPKEY",
            transport = CustomUriRedirectTransport(callbackFlow),
            authorizationLauncher = launcher,
        )

        val result = flow.connect()

        assertIs<Result.Ok<OAuthTokens>>(result)
        assertEquals("AT", result.value.accessToken)
        assertTrue(launcher.lastAuthorizeUrl.orEmpty().startsWith("https://www.dropbox.com/oauth2/authorize"))
        assertTrue(launcher.lastAuthorizeUrl.orEmpty().contains("client_id=APPKEY"))
        assertEquals("keryx://oauth2/callback", launcher.lastRedirectUri)
    }

    @Test
    fun connectFailsOnStateMismatch() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val launcher = FakeAuthorizationLauncher(callbackFlow) {
            OAuthCallbackParams(code = "CODE", state = "wrong-state", error = null, errorDescription = null)
        }
        val authManager = DropboxAuthManager(tokenClient())
        val flow = OAuthConnectFlow(
            authManager,
            clientId = "APPKEY",
            transport = CustomUriRedirectTransport(callbackFlow),
            authorizationLauncher = launcher,
        )

        val result = flow.connect()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }

    @Test
    fun connectFailsWhenCallbackCarriesProviderError() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val launcher = FakeAuthorizationLauncher(callbackFlow) { state ->
            OAuthCallbackParams(code = null, state = state, error = "access_denied", errorDescription = "User declined")
        }
        val authManager = DropboxAuthManager(tokenClient())
        val flow = OAuthConnectFlow(
            authManager,
            clientId = "APPKEY",
            transport = CustomUriRedirectTransport(callbackFlow),
            authorizationLauncher = launcher,
        )

        val result = flow.connect()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }

    @Test
    fun connectTimesOutWhenNoCallbackArrives() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        // A launcher that never delivers a callback — the transport must time out rather than hang.
        val launcher = AuthorizationLauncher { _, _ -> }
        val authManager = DropboxAuthManager(tokenClient())
        val flow = OAuthConnectFlow(
            authManager,
            clientId = "APPKEY",
            transport = CustomUriRedirectTransport(callbackFlow),
            timeoutMillis = 10,
            authorizationLauncher = launcher,
        )

        val result = flow.connect()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }

    @Test
    fun connectFailsWhenCallbackCarriesNoCode() = runTest {
        val callbackFlow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val launcher = FakeAuthorizationLauncher(callbackFlow) { state ->
            OAuthCallbackParams(code = null, state = state, error = null, errorDescription = null)
        }
        val authManager = DropboxAuthManager(tokenClient())
        val flow = OAuthConnectFlow(
            authManager,
            clientId = "APPKEY",
            transport = CustomUriRedirectTransport(callbackFlow),
            authorizationLauncher = launcher,
        )

        val result = flow.connect()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
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
