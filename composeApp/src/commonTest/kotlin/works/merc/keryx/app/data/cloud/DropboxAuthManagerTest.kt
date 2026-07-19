package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DropboxAuthManagerTest {

    private val fixedClock = Clock { 1_000_000L }

    private fun manager(handler: MockRequestHandler): DropboxAuthManager {
        val client = HttpClient(MockEngine(handler)) { expectSuccess = false }
        return DropboxAuthManager(client, clock = fixedClock)
    }

    @Test
    fun buildsAuthorizeUrlWithPkceAndOfflineAccess() {
        val url = manager { respond("{}", HttpStatusCode.OK) }
            .buildAuthorizeUrl("APPKEY", "http://127.0.0.1:1234/callback", "CHAL", "STATE")
        assertTrue(url.startsWith("https://www.dropbox.com/oauth2/authorize"))
        assertTrue(url.contains("client_id=APPKEY"))
        assertTrue(url.contains("code_challenge=CHAL"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("token_access_type=offline"))
        assertTrue(url.contains("state=STATE"))
    }

    @Test
    fun exchangeCodeParsesTokens() = runTest {
        val body = """{"access_token":"AT","refresh_token":"RT","expires_in":14400}"""
        val m = manager { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val r = m.exchangeCode("APPKEY", "code", "verifier", "http://127.0.0.1/callback")
        assertIs<Result.Ok<OAuthTokens>>(r)
        assertEquals("AT", r.value.accessToken)
        assertEquals("RT", r.value.refreshToken)
        assertEquals(1_000_000L + 14400L * 1000, r.value.expiresAtMillis)
    }

    @Test
    fun refreshKeepsExistingRefreshTokenWhenNoneReturned() = runTest {
        val body = """{"access_token":"AT2","expires_in":14400}"""
        val m = manager { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val r = m.refresh("APPKEY", "RT-old")
        assertIs<Result.Ok<OAuthTokens>>(r)
        assertEquals("AT2", r.value.accessToken)
        assertEquals("RT-old", r.value.refreshToken)
    }

    @Test
    fun revokeSendsBearerTokenAndReturnsOkOnSuccess() = runTest {
        var authHeader: String? = null
        val m = manager { request ->
            authHeader = request.headers["Authorization"]
            respond("", HttpStatusCode.OK)
        }
        val r = m.revoke("AT")
        assertIs<Result.Ok<Unit>>(r)
        assertEquals("Bearer AT", authHeader)
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
        val r = m.exchangeCode("APPKEY", "code", "verifier", "http://127.0.0.1/callback")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun refreshReturnsAuthErrorOnFailureStatus() = runTest {
        val m = manager { respond("""{"error":"invalid_grant"}""", HttpStatusCode.Unauthorized) }
        val r = m.refresh("APPKEY", "RT-old")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun exchangeCodeReturnsErrOnMalformedJsonBody() = runTest {
        val m = manager { respond("not json at all", HttpStatusCode.OK) }
        val r = m.exchangeCode("APPKEY", "code", "verifier", "http://127.0.0.1/callback")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun exchangeCodeReturnsErrWhenAccessTokenMissing() = runTest {
        val body = """{"refresh_token":"RT","expires_in":14400}"""
        val m = manager { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val r = m.exchangeCode("APPKEY", "code", "verifier", "http://127.0.0.1/callback")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals("Token response had no access_token", r.exception.message)
    }
}
