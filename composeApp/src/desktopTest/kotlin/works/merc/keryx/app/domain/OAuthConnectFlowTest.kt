package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * [OAuthConnectFlow.connect] mostly orchestrates a real system browser launch
 * ([works.merc.keryx.app.platform.BrowserOpener], an `actual` object with no seam
 * for injection) and, depending on the [OAuthRedirectTransport], either a custom-URI
 * callback or a real loopback HTTP server. It cannot be exercised end-to-end from a
 * unit test without actually opening a browser window, so only the pure, pre-flight
 * branch (missing/blank client id short-circuits before any side effect) is covered
 * here. The browser-open / callback-wait / code-exchange path is left untested at
 * this layer.
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
}
