package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.OAuthTokens
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class NextcloudAppPasswordTest {

    private val creds = AppPasswordCredentials(
        serverUrl = "https://cloud.example.com/nc",
        userId = "alice",
        loginName = "alice@example.com",
        appPassword = "app-pass",
    )

    private fun run(
        handler: (HttpRequestData) -> HttpStatusCode,
        history: MutableList<HttpRequestData> = mutableListOf(),
        c: works.merc.keryx.app.data.cloud.CloudCredentials = creds,
    ): Result<Unit> = runBlocking {
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            respond("", handler(request))
        }) { expectSuccess = false }
        revokeNextcloudAppPassword(client, c, "Keryx/1.0")
    }

    @Test
    fun successDeletesTheAppPasswordEndpointWithOcsHeaders() {
        val history = mutableListOf<HttpRequestData>()
        val r = run(handler = { HttpStatusCode.OK }, history = history)
        assertEquals(Result.Ok(Unit), r)
        val req = history.single()
        assertEquals(HttpMethod.Delete, req.method)
        assertEquals("https://cloud.example.com/nc/ocs/v2.php/core/apppassword", req.url.toString())
        val expectedAuth = "Basic " + Base64.encode("alice@example.com:app-pass".encodeToByteArray())
        assertEquals(expectedAuth, req.headers[HttpHeaders.Authorization])
        assertEquals("true", req.headers["OCS-APIREQUEST"])
        assertEquals("application/json", req.headers[HttpHeaders.Accept])
        assertEquals("Keryx/1.0", req.headers[HttpHeaders.UserAgent])
    }

    @Test
    fun failureStatusesAreStorageErrorsWithTheStatus() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            val r = run(handler = { status })
            assertIs<Result.Err>(r)
            val e = assertIs<CloudStorageException>(r.exception)
            assertEquals("Nextcloud app password revoke failed (HTTP ${status.value})", e.message)
        }
    }

    @Test
    fun networkErrorIsAStorageErrorCarryingItsMessage() {
        val client = HttpClient(MockEngine { throw IllegalStateException("socket closed") }) { expectSuccess = false }
        val r = runBlocking { revokeNextcloudAppPassword(client, creds, "ua") }
        assertIs<Result.Err>(r)
        assertEquals("socket closed", assertIs<CloudStorageException>(r.exception).message)
    }

    @Test
    fun nonNextcloudCredentialsAreRejectedWithoutARequest() {
        val history = mutableListOf<HttpRequestData>()
        val r = run(handler = { HttpStatusCode.OK }, history = history, c = OAuthTokens("AT"))
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertTrue(history.isEmpty())
    }

    @Test
    fun aNonHttpsServerIsRejectedWithoutARequest() {
        val history = mutableListOf<HttpRequestData>()
        val r = run(handler = { HttpStatusCode.OK }, history = history, c = creds.copy(serverUrl = "http://cloud.example.com"))
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertTrue(history.isEmpty())
    }
}
