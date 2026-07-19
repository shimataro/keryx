package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleDriveAuthManagerTest {

    private val fixedClock = Clock { 1_000_000L }

    private fun manager(handler: MockRequestHandler): GoogleDriveAuthManager {
        val client = HttpClient(MockEngine(handler)) { expectSuccess = false }
        return GoogleDriveAuthManager(client, clientSecret = "SECRET", clock = fixedClock)
    }

    @Test
    fun buildsAuthorizeUrlWithPkceOfflineAccessAndConsentPrompt() {
        val url = manager { respond("{}", HttpStatusCode.OK) }
            .buildAuthorizeUrl("CLIENTID", "http://127.0.0.1:1234/", "CHAL", "STATE")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(url.contains("client_id=CLIENTID"))
        assertTrue(url.contains("code_challenge=CHAL"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
        assertTrue(url.contains("state=STATE"))
        assertTrue(url.contains("scope=") && url.contains("drive.appdata"))
    }

    @Test
    fun exchangeCodeParsesTokens() = runTest {
        val body = """{"access_token":"AT","refresh_token":"RT","expires_in":3600}"""
        val m = manager { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val r = m.exchangeCode("CLIENTID", "code", "verifier", "http://127.0.0.1:1234/")
        assertIs<Result.Ok<OAuthTokens>>(r)
        assertEquals("AT", r.value.accessToken)
        assertEquals("RT", r.value.refreshToken)
        assertEquals(1_000_000L + 3600L * 1000, r.value.expiresAtMillis)
    }

    @Test
    fun refreshKeepsExistingRefreshTokenWhenNoneReturned() = runTest {
        val body = """{"access_token":"AT2","expires_in":3600}"""
        val m = manager { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val r = m.refresh("CLIENTID", "RT-old")
        assertIs<Result.Ok<OAuthTokens>>(r)
        assertEquals("AT2", r.value.accessToken)
        assertEquals("RT-old", r.value.refreshToken)
    }

    @Test
    fun exchangeCodeSendsClientSecret() = runTest {
        // Google's token endpoint rejects "Desktop app" clients without it, even with PKCE.
        var sentBody: String? = null
        val client = HttpClient(MockEngine { request ->
            sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"access_token":"AT"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { expectSuccess = false }
        GoogleDriveAuthManager(client, clientSecret = "SECRET", clock = fixedClock)
            .exchangeCode("CLIENTID", "code", "verifier", "http://127.0.0.1:1234/")
        assertTrue(sentBody?.contains("client_secret=SECRET") == true)
    }

    @Test
    fun refreshSendsClientSecret() = runTest {
        var sentBody: String? = null
        val client = HttpClient(MockEngine { request ->
            sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"access_token":"AT2"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { expectSuccess = false }
        GoogleDriveAuthManager(client, clientSecret = "SECRET", clock = fixedClock).refresh("CLIENTID", "RT-old")
        assertTrue(sentBody?.contains("client_secret=SECRET") == true)
    }

    @Test
    fun revokeSendsTokenAsFormParameterAndReturnsOkOnSuccess() = runTest {
        var body: String? = null
        val m = manager { request ->
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("", HttpStatusCode.OK)
        }
        val r = m.revoke("AT")
        assertIs<Result.Ok<Unit>>(r)
        assertTrue(body?.contains("token=AT") == true)
    }

    @Test
    fun revokeReturnsErrOnFailureStatus() = runTest {
        val m = manager { respond("", HttpStatusCode.BadRequest) }
        val r = m.revoke("AT")
        assertIs<Result.Err>(r)
    }

    @Test
    fun exchangeCodeReturnsAuthErrorOnFailureStatus() = runTest {
        val m = manager { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }
        val r = m.exchangeCode("CLIENTID", "code", "verifier", "http://127.0.0.1:1234/")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun refreshReturnsAuthErrorOnFailureStatus() = runTest {
        val m = manager { respond("""{"error":"invalid_grant"}""", HttpStatusCode.Unauthorized) }
        val r = m.refresh("CLIENTID", "RT-old")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun exchangeCodeReturnsErrOnMalformedJsonBody() = runTest {
        val m = manager { respond("not json at all", HttpStatusCode.OK) }
        val r = m.exchangeCode("CLIENTID", "code", "verifier", "http://127.0.0.1:1234/")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun exchangeCodeReturnsErrWhenAccessTokenMissing() = runTest {
        val body = """{"refresh_token":"RT","expires_in":3600}"""
        val m = manager { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val r = m.exchangeCode("CLIENTID", "code", "verifier", "http://127.0.0.1:1234/")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals("Token response had no access_token", r.exception.message)
    }
}
