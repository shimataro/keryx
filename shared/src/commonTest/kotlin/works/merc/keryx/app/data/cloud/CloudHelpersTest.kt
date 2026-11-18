package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `OAuthTokens.kt`'s [buildPkceAuthorizeUrl] and `CloudStorage.kt`'s [okOr] / [okOrAbsentOr] / [cloudFileName]. */
class CloudHelpersTest {

    private fun queryNames(url: String): List<String> =
        url.substringAfter('?').split('&').map { it.substringBefore('=') }

    @Test
    fun pkceUrlHasTheBaseParametersInOrder() {
        val url = buildPkceAuthorizeUrl("https://auth.example/authorize", "CID", "app://cb", "CHAL", "STATE", "the-scope")
        assertTrue(url.startsWith("https://auth.example/authorize?"))
        assertEquals(
            listOf("client_id", "response_type", "redirect_uri", "code_challenge", "code_challenge_method", "scope", "state"),
            queryNames(url),
        )
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
    }

    @Test
    fun pkceUrlInsertsExtraParametersBetweenChallengeMethodAndScope() {
        val url = buildPkceAuthorizeUrl("https://auth.example/authorize", "CID", "app://cb", "CHAL", "STATE", "s") {
            append("extra_a", "1")
            append("extra_b", "2")
        }
        assertEquals(
            listOf(
                "client_id", "response_type", "redirect_uri", "code_challenge", "code_challenge_method",
                "extra_a", "extra_b", "scope", "state",
            ),
            queryNames(url),
        )
    }

    @Test
    fun dropboxAuthorizeUrlHasTheFullOrderedParameterList() {
        val url = DropboxAuthManager(HttpClient(MockEngine { respond("{}") }))
            .buildAuthorizeUrl("CID", "app://cb", "CHAL", "STATE")
        assertEquals(
            listOf(
                "client_id", "response_type", "redirect_uri", "code_challenge", "code_challenge_method",
                "token_access_type", "scope", "state",
            ),
            queryNames(url),
        )
    }

    @Test
    fun googleAuthorizeUrlHasTheFullOrderedParameterList() {
        val url = GoogleDriveAuthManager(HttpClient(MockEngine { respond("{}") }), clientSecret = "SECRET")
            .buildAuthorizeUrl("CID", "app://cb", "CHAL", "STATE")
        assertEquals(
            listOf(
                "client_id", "response_type", "redirect_uri", "code_challenge", "code_challenge_method",
                "access_type", "prompt", "scope", "state",
            ),
            queryNames(url),
        )
    }

    @Test
    fun oneDriveAuthorizeUrlHasTheFullOrderedParameterList() {
        val url = OneDriveAuthManager(HttpClient(MockEngine { respond("{}") }))
            .buildAuthorizeUrl("CID", "app://cb", "CHAL", "STATE")
        assertEquals(
            listOf("client_id", "response_type", "redirect_uri", "code_challenge", "code_challenge_method", "scope", "state"),
            queryNames(url),
        )
    }

    private fun response(status: HttpStatusCode, body: String = ""): HttpResponse = runBlocking {
        HttpClient(MockEngine { respond(body, status) }) { expectSuccess = false }.get("https://example.test/x")
    }

    @Test
    fun okOrMapsA2xxToOk() = runBlocking {
        assertEquals(Result.Ok(Unit), response(HttpStatusCode.OK).okOr("P"))
        assertEquals(Result.Ok(Unit), response(HttpStatusCode.NoContent).okOr("P"))
    }

    @Test
    fun okOrTreatsA404AsAnError() = runBlocking {
        val r = response(HttpStatusCode.NotFound, "missing").okOr("P")
        assertIs<Result.Err>(r)
        val e = assertIs<CloudStorageException>(r.exception)
        assertEquals("P error (HTTP 404): missing", e.message)
    }

    @Test
    fun okOrMapsAuthStatusesToCloudAuthException() = runBlocking {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val r = response(status).okOr("P")
            assertIs<Result.Err>(r)
            assertIs<CloudAuthException>(r.exception)
        }
    }

    @Test
    fun okOrMapsOtherStatusesToAStorageErrorNamingTheProvider() = runBlocking {
        val r = response(HttpStatusCode.InternalServerError, "boom").okOr("Prov")
        assertIs<Result.Err>(r)
        assertEquals("Prov error (HTTP 500): boom", assertIs<CloudStorageException>(r.exception).message)
    }

    @Test
    fun okOrAbsentOrAcceptsA2xxAnd404() = runBlocking {
        assertEquals(Result.Ok(Unit), response(HttpStatusCode.OK).okOrAbsentOr("P"))
        assertEquals(Result.Ok(Unit), response(HttpStatusCode.NotFound).okOrAbsentOr("P"))
    }

    @Test
    fun okOrAbsentOrMapsOtherStatusesLikeOkOr() = runBlocking {
        val auth = response(HttpStatusCode.Unauthorized).okOrAbsentOr("P")
        assertIs<Result.Err>(auth)
        assertIs<CloudAuthException>(auth.exception)

        val server = response(HttpStatusCode.BadGateway, "bad").okOrAbsentOr("P")
        assertIs<Result.Err>(server)
        assertEquals("P error (HTTP 502): bad", assertIs<CloudStorageException>(server.exception).message)
    }

    @Test
    fun cloudFileNameIsTheBasename() {
        assertEquals("keryx.db.gz", cloudFileName("Keryx/keryx.db.gz"))
        assertEquals("keryx.db.gz", cloudFileName("/a/b/keryx.db.gz"))
        assertEquals("keryx.db.gz", cloudFileName("keryx.db.gz"))
        assertEquals("", cloudFileName("dir/"))
    }
}
