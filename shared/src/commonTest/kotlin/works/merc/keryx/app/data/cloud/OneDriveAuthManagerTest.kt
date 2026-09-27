package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OneDriveAuthManagerTest {

    private val fixedClock = Clock { 1_000_000L }

    private fun manager(handler: MockRequestHandler): OneDriveAuthManager {
        val client = HttpClient(MockEngine(handler)) { expectSuccess = false }
        return OneDriveAuthManager(client, clock = fixedClock)
    }

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    @Test
    fun buildsAuthorizeUrlWithPkceAndAppFolderScope() {
        val url = manager { respond("{}", HttpStatusCode.OK) }
            .buildAuthorizeUrl("CID", "keryx://oauth2/callback", "CHAL", "STATE")
        // The tenant segment must be `consumers`, never `common`: the app registration is
        // personal-accounts-only, and Microsoft rejects that audience on /common (see Constants.kt).
        assertTrue(url.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"))
        assertTrue(url.contains("client_id=CID"))
        assertTrue(url.contains("code_challenge=CHAL"))
        assertTrue(url.contains("code_challenge_method=S256"))
        // Scope carries both the app-folder scope and offline_access (for a refresh token).
        assertTrue(url.contains("Files.ReadWrite.AppFolder"))
        assertTrue(url.contains("offline_access"))
        assertTrue(url.contains("state=STATE"))
    }

    @Test
    fun exchangeCodeParsesTokens() = runTest {
        val body = """{"access_token":"AT","refresh_token":"RT","expires_in":3600}"""
        val m = manager { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val r = m.exchangeCode("CID", "code", "verifier", "keryx://oauth2/callback")
        assertIs<Result.Ok<OAuthTokens>>(r)
        assertEquals("AT", r.value.accessToken)
        assertEquals("RT", r.value.refreshToken)
        assertEquals(1_000_000L + 3600L * 1000, r.value.expiresAtMillis)
    }

    @Test
    fun exchangeCodeSendsScopeAndVerifierButNoClientSecret() = runTest {
        var body: String? = null
        var tokenUrl: String? = null
        val m = manager { request ->
            body = (request.body as FormDataContent).bytes().decodeToString()
            tokenUrl = request.url.toString()
            respond("""{"access_token":"AT","refresh_token":"RT","expires_in":3600}""", HttpStatusCode.OK, jsonHeaders)
        }
        val r = m.exchangeCode("CID", "code", "verifier", "keryx://oauth2/callback")
        assertIs<Result.Ok<OAuthTokens>>(r)
        // The token endpoint must use the same `consumers` tenant as the authorize URL — a code
        // obtained from one tenant segment cannot be redeemed at another.
        assertTrue(tokenUrl!!.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
        val form = body!!
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("client_id=CID"))
        assertTrue(form.contains("code_verifier=verifier"))
        assertTrue(form.contains("scope="))
        // OneDrive is a PKCE public client — no client secret is ever sent (unlike Google).
        assertFalse(form.contains("client_secret"))
    }

    @Test
    fun refreshKeepsExistingRefreshTokenWhenNoneReturned() = runTest {
        val body = """{"access_token":"AT2","expires_in":3600}"""
        val m = manager { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val r = m.refresh("CID", "RT-old")
        assertIs<Result.Ok<OAuthTokens>>(r)
        assertEquals("AT2", r.value.accessToken)
        assertEquals("RT-old", r.value.refreshToken)
    }

    @Test
    fun revokeIsNoOpSuccessWithoutHttpCall() = runTest {
        var called = false
        val m = manager { called = true; respond("", HttpStatusCode.OK) }
        val r = m.revoke("AT")
        assertIs<Result.Ok<Unit>>(r)
        assertFalse(called, "revoke must not hit the network — Microsoft has no revoke endpoint")
    }

    @Test
    fun exchangeCodeReturnsAuthErrorOnFailureStatus() = runTest {
        val m = manager { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }
        val r = m.exchangeCode("CID", "code", "verifier", "keryx://oauth2/callback")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun refreshReturnsAuthErrorOnFailureStatus() = runTest {
        val m = manager { respond("""{"error":"invalid_grant"}""", HttpStatusCode.Unauthorized) }
        val r = m.refresh("CID", "RT-old")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun exchangeCodeReturnsErrWhenAccessTokenMissing() = runTest {
        val body = """{"refresh_token":"RT","expires_in":3600}"""
        val m = manager { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val r = m.exchangeCode("CID", "code", "verifier", "keryx://oauth2/callback")
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals("Token response had no access_token", r.exception.message)
    }

    @Test
    fun cancellationPropagatesNotConvertedToError() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val m = manager {
            started.complete(Unit)
            gate.await()
            respond(
                """{"access_token":"AT","refresh_token":"RT","expires_in":3600}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        var result: Result<OAuthTokens>? = null
        val job = launch { result = m.exchangeCode("CID", "code", "verifier", "keryx://oauth2/callback") }
        runCurrent()
        started.await()
        job.cancel()
        job.join()
        assertNull(result)
    }
}
