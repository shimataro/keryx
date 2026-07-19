package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CLOUD_DB_PATH
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DropboxStorageTest {

    private fun storage(token: String? = "tok", handler: MockRequestHandler): DropboxStorage {
        val client = HttpClient(MockEngine(handler)) { expectSuccess = false }
        return DropboxStorage(client, accessTokenProvider = { token })
    }

    @Test
    fun downloadReturnsBytesAndRev() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val s = storage { respond(bytes, HttpStatusCode.OK, headersOf("dropbox-api-result", """{"rev":"r42"}""")) }
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Ok<CloudFile>>(r)
        assertEquals("r42", r.value.rev)
        assertContentEquals(bytes, r.value.data)
    }

    @Test
    fun uploadConflictMapsToSyncConflict() = runTest {
        val s = storage { respondError(HttpStatusCode.Conflict) }
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = "r1")
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
    }

    @Test
    fun uploadSuccess() = runTest {
        val s = storage { respond("{}", HttpStatusCode.OK) }
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
    }

    @Test
    fun existsTrueAndFalse() = runTest {
        val yes = storage { respond("""{".tag":"file"}""", HttpStatusCode.OK) }
        assertEquals(true, (yes.exists(CLOUD_DB_PATH) as Result.Ok).value)

        val no = storage { respond("""{"error_summary":"path/not_found/..."}""", HttpStatusCode.Conflict) }
        assertEquals(false, (no.exists(CLOUD_DB_PATH) as Result.Ok).value)
    }

    @Test
    fun missingTokenIsAuthError() = runTest {
        val s = storage(token = null) { respond("{}", HttpStatusCode.OK) }
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun unauthorizedMapsToAuthError() = runTest {
        val s = storage { respondError(HttpStatusCode.Unauthorized) }
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun downloadMissingResultHeaderIsCloudStorageError() = runTest {
        val s = storage { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("Missing Dropbox-API-Result header", r.exception.message)
    }

    @Test
    fun downloadResultHeaderMissingRevIsCloudStorageError() = runTest {
        val s = storage {
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf("dropbox-api-result", """{"name":"keryx.db"}"""))
        }
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("Missing rev in metadata", r.exception.message)
    }

    @Test
    fun uploadUnauthorizedMapsToAuthError() = runTest {
        val s = storage { respondError(HttpStatusCode.Unauthorized) }
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun uploadForbiddenMapsToAuthError() = runTest {
        val s = storage { respondError(HttpStatusCode.Forbidden) }
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun uploadServerErrorMapsToCloudStorageError() = runTest {
        val s = storage { respondError(HttpStatusCode.InternalServerError) }
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun existsConflictWithOtherErrorTagSurfacesAsError() = runTest {
        val s = storage { respond("""{"error_summary":"some_other_error/..."}""", HttpStatusCode.Conflict) }
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun downloadNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val s = storage { throw IOException("boom") }
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun uploadNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val s = storage { throw IOException("boom") }
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun existsNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val s = storage { throw IOException("boom") }
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }
}
