package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.MILLIS_PER_MINUTE
import works.merc.keryx.app.core.NEXTCLOUD_LOGIN_TIMEOUT_MS
import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NextcloudServerProblem
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.CloudCredentials
import works.merc.keryx.app.domain.CloudConnectRequest
import works.merc.keryx.app.domain.LoginPageLauncher
import works.merc.keryx.app.platform.platformOsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NextcloudLoginFlowTest {

    private class RecordingLauncher : LoginPageLauncher {
        val opened = mutableListOf<String>()
        var closeCount = 0
        override fun open(url: String) {
            opened += url
        }
        override fun close() {
            closeCount++
        }
    }

    private val history = mutableListOf<HttpRequestData>()
    private val launcher = RecordingLauncher()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /**
     * A client whose mock engine runs on the test scheduler, so the flow's poll delays and its
     * timeout advance in virtual time without a request in flight on another thread letting the
     * scheduler skip ahead to the timeout.
     */
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val config = MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                history.add(request)
                handler(request)
            }
        }
        return HttpClient(MockEngine(config)) {
            expectSuccess = false
            followRedirects = false
        }
    }

    private fun TestScope.flow(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NextcloudLoginFlow =
        NextcloudLoginFlow(client(handler), launcher, userAgent = "Keryx (Test)", pollIntervalMs = 1_000)

    private fun initiateBody(
        login: String = "https://cloud.example.com/login/v2/flow/abc",
        endpoint: String = "https://cloud.example.com/login/v2/poll",
    ) = """{"poll":{"token":"tok123","endpoint":"$endpoint"},"login":"$login"}"""

    private fun grantBody(server: String = "https://cloud.example.com") =
        """{"server":"$server","loginName":"alice@example.com","appPassword":"secret-app-pass"}"""

    private val userBody = """{"ocs":{"meta":{"status":"ok"},"data":{"id":"alice","display-name":"Alice"}}}"""

    private fun request(url: String? = "cloud.example.com") = CloudConnectRequest(serverUrl = url)

    private val HttpRequestData.path: String get() = url.encodedPath

    private suspend fun MockRequestHandleScope.standard(
        request: HttpRequestData,
        pendingPolls: Int,
        polls: IntArray,
        grant: String = grantBody(),
        userStatus: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = when {
        request.path.endsWith("/index.php/login/v2") -> respond(initiateBody(), HttpStatusCode.OK, jsonHeaders)
        request.path.endsWith("/login/v2/poll") -> {
            polls[0]++
            if (polls[0] <= pendingPolls) respond("", HttpStatusCode.NotFound) else respond(grant, HttpStatusCode.OK, jsonHeaders)
        }
        request.path.endsWith("/ocs/v1.php/cloud/user") -> respond(userBody, userStatus, jsonHeaders)
        request.path.endsWith("/ocs/v2.php/core/apppassword") -> respond("", HttpStatusCode.OK)
        else -> respond("", HttpStatusCode.NotFound)
    }

    @Test
    fun pollsUntilGrantedAndReadsTheUserId() = runTest {
        val polls = IntArray(1)
        val f = flow { standard(it, pendingPolls = 2, polls = polls) }
        val result = f.connect(request("https://cloud.example.com/index.php/login"))
        val credentials = assertIs<AppPasswordCredentials>(assertIs<Result.Ok<CloudCredentials>>(result).value)
        assertEquals(AppPasswordCredentials("https://cloud.example.com", "alice", "alice@example.com", "secret-app-pass"), credentials)
        assertEquals(3, polls[0])
        assertEquals(listOf("https://cloud.example.com/login/v2/flow/abc"), launcher.opened)
        assertEquals(1, launcher.closeCount)

        val initiate = history.first()
        assertEquals("POST", initiate.method.value)
        assertEquals("https://cloud.example.com/index.php/login/v2", initiate.url.toString())
        assertEquals("Keryx (Test)", initiate.headers[HttpHeaders.UserAgent])

        val poll = history.first { it.path.endsWith("/poll") }
        assertEquals("POST", poll.method.value)
        assertEquals("token=tok123", (poll.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

        val user = history.last()
        assertEquals("true", user.headers["OCS-APIREQUEST"])
        assertTrue(user.headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "))
        assertEquals("json", user.url.parameters["format"])
    }

    @Test
    fun pollsAboutOncePerInterval() = runTest {
        val polls = IntArray(1)
        val f = flow { standard(it, pendingPolls = Int.MAX_VALUE, polls = polls) }
        val result = async { f.connect(request()) }
        runCurrent()
        advanceTimeBy(5_500)
        assertEquals(6, polls[0])
        result.cancel()
    }

    @Test
    fun timesOutAfterTwentyMinutesAndClosesThePage() = runTest {
        assertEquals(20 * MILLIS_PER_MINUTE, NEXTCLOUD_LOGIN_TIMEOUT_MS)
        val polls = IntArray(1)
        val f = NextcloudLoginFlow(client { standard(it, pendingPolls = Int.MAX_VALUE, polls = polls) }, launcher, userAgent = "Keryx (Test)")
        val result = f.connect(request())
        assertIs<CloudAuthException>(assertIs<Result.Err>(result).exception)
        assertTrue(currentTime >= NEXTCLOUD_LOGIN_TIMEOUT_MS, "ended at $currentTime")
        assertEquals(1, launcher.closeCount)
    }

    @Test
    fun aFailedPollRequestIsRetried() = runTest {
        var polls = 0
        val f = flow { request ->
            when {
                request.path.endsWith("/index.php/login/v2") -> respond(initiateBody(), HttpStatusCode.OK, jsonHeaders)
                request.path.endsWith("/poll") -> {
                    polls++
                    when (polls) {
                        1 -> throw kotlinx.io.IOException("network blip")
                        2 -> respond("", HttpStatusCode.ServiceUnavailable)
                        else -> respond(grantBody(), HttpStatusCode.OK, jsonHeaders)
                    }
                }
                else -> respond(userBody, HttpStatusCode.OK, jsonHeaders)
            }
        }
        assertIs<Result.Ok<CloudCredentials>>(f.connect(request()))
        assertEquals(3, polls)
    }

    @Test
    fun cancellationClosesThePage() = runTest {
        val polled = CompletableDeferred<Unit>()
        val f = flow { request ->
            if (request.path.endsWith("/poll")) polled.complete(Unit)
            standard(request, pendingPolls = Int.MAX_VALUE, polls = IntArray(1))
        }
        val result = async { f.connect(request()) }
        polled.await()
        result.cancel()
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, launcher.closeCount)
    }

    @Test
    fun aRequestWithoutAServerUrlIsRejectedWithoutARequest() = runTest {
        val f = flow { respond("", HttpStatusCode.OK) }
        assertIs<CloudAuthException>(assertIs<Result.Err>(f.connect(CloudConnectRequest.NONE)).exception)
        assertTrue(history.isEmpty())
        assertEquals(0, launcher.closeCount)
    }

    @Test
    fun aNonHttpsServerUrlIsRejectedWithoutARequest() = runTest {
        val f = flow { respond("", HttpStatusCode.OK) }
        val error = assertIs<Result.Err>(f.connect(request("http://cloud.example.com"))).exception
        assertEquals(NextcloudServerProblem.HTTPS_REQUIRED, assertIs<NextcloudServerException>(error).problem)
        assertTrue(history.isEmpty())
    }

    @Test
    fun aNonHttpsPollEndpointOrLoginPageIsRejectedBeforeOpeningAnything() = runTest {
        for (body in listOf(initiateBody(endpoint = "http://cloud.example.com/login/v2/poll"), initiateBody(login = "http://cloud.example.com/flow"))) {
            history.clear()
            val f = flow { respond(body, HttpStatusCode.OK, jsonHeaders) }
            assertIs<CloudAuthException>(assertIs<Result.Err>(f.connect(request())).exception)
            assertEquals(1, history.size)
            assertTrue(launcher.opened.isEmpty())
            assertEquals(0, launcher.closeCount)
        }
    }

    @Test
    fun aGrantForANonHttpsServerIsRejectedAndNothingIsSentThere() = runTest {
        val polls = IntArray(1)
        val f = flow { standard(it, pendingPolls = 0, polls = polls, grant = grantBody(server = "http://cloud.example.com")) }
        assertIs<CloudAuthException>(assertIs<Result.Err>(f.connect(request())).exception)
        assertTrue(history.none { it.url.protocol.name == "http" })
        assertTrue(history.none { it.path.contains("/ocs/") })
        assertEquals(1, launcher.closeCount)
    }

    @Test
    fun anUnreadableUserIdFailsAndRevokesTheIssuedPassword() = runTest {
        val polls = IntArray(1)
        val f = flow { standard(it, pendingPolls = 0, polls = polls, userStatus = HttpStatusCode.InternalServerError) }
        assertIs<CloudAuthException>(assertIs<Result.Err>(f.connect(request())).exception)
        val revoke = history.last()
        assertEquals("DELETE", revoke.method.value)
        assertEquals("https://cloud.example.com/ocs/v2.php/core/apppassword", revoke.url.toString())
        assertEquals("true", revoke.headers["OCS-APIREQUEST"])
        assertEquals(1, launcher.closeCount)
    }

    @Test
    fun aUserIdRequestThatThrowsFailsAndRevokesTheIssuedPassword() = runTest {
        val polls = IntArray(1)
        val f = flow { request ->
            if (request.path.endsWith("/ocs/v1.php/cloud/user")) throw IllegalStateException("connection reset")
            standard(request, pendingPolls = 0, polls = polls)
        }
        assertIs<CloudAuthException>(assertIs<Result.Err>(f.connect(request())).exception)
        val revoke = history.last()
        assertEquals("DELETE", revoke.method.value)
        assertEquals("https://cloud.example.com/ocs/v2.php/core/apppassword", revoke.url.toString())
        assertEquals(1, launcher.closeCount)
    }

    @Test
    fun aServerWithoutLoginFlowFails() = runTest {
        val f = flow { respond("", HttpStatusCode.NotFound) }
        assertIs<CloudAuthException>(assertIs<Result.Err>(f.connect(request())).exception)
        assertTrue(launcher.opened.isEmpty())
    }

    @Test
    fun theClientNameNamesTheAppAndTheOs() {
        assertEquals("Keryx ($platformOsName)", nextcloudClientName())
        assertTrue(platformOsName in setOf("Windows", "macOS", "Linux", "Android", "iOS"), platformOsName)
    }
}
