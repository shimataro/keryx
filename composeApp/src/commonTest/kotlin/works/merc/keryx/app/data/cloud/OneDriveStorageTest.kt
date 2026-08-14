package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OneDriveStorage] is path-addressed (Graph app folder), so each operation is a single
 * request except [OneDriveStorage.download], which reads metadata then fetches the
 * pre-authenticated `@microsoft.graph.downloadUrl`. The client mirrors production DI
 * (`followRedirects=false`) so the manual single-redirect follow is exercised.
 */
class OneDriveStorageTest {

    private fun storage(
        token: String? = "tok",
        vararg responses: MockRequestHandler,
    ): Triple<OneDriveStorage, MutableList<HttpRequestData>, () -> Unit> {
        val history = mutableListOf<HttpRequestData>()
        val queue = ArrayDeque(responses.toList())
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            queue.removeFirst().invoke(this, request)
        }) {
            expectSuccess = false
            followRedirects = false
        }
        val verify = { assertTrue(queue.isEmpty(), "Unconsumed mock response handlers remain") }
        return Triple(OneDriveStorage(client, accessTokenProvider = { token }), history, verify)
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun meta(eTag: String = "etag1", downloadUrl: String = "https://dl.example/blob") =
        """{"eTag":"$eTag","@microsoft.graph.downloadUrl":"$downloadUrl"}"""

    @Test
    fun downloadReturnsBytesAndRev() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val (s, history, verify) = storage(
            "tok",
            { respond(meta(eTag = "etag42"), HttpStatusCode.OK, jsonHeaders) },
            { respond(bytes, HttpStatusCode.OK) },
        )
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_PATH, dest)
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("etag42", r.value.rev)
        assertContentEquals(bytes, bytesAt(dest))
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertTrue(history[0].url.toString().contains("approot"))
        assertTrue(history[0].url.toString().contains("keryx.db"))
        // The metadata request is authenticated; the pre-authenticated download URL is not.
        assertEquals("Bearer tok", history[0].headers["Authorization"])
        assertEquals("GET", history[1].method.value)
        assertEquals("https://dl.example/blob", history[1].url.toString())
        assertNull(history[1].headers["Authorization"])
        verify()
    }

    @Test
    fun downloadFollowsSingleRedirect() = runTest {
        val bytes = byteArrayOf(9, 8, 7)
        val (s, history, verify) = storage(
            "tok",
            { respond(meta(eTag = "etagR"), HttpStatusCode.OK, jsonHeaders) },
            { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example/x")) },
            { respond(bytes, HttpStatusCode.OK) },
        )
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_PATH, dest)
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("etagR", r.value.rev)
        assertContentEquals(bytes, bytesAt(dest))
        assertEquals(3, history.size)
        assertEquals("https://cdn.example/x", history[2].url.toString())
        verify()
    }

    @Test
    fun downloadFileNotFoundIsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { respondError(HttpStatusCode.NotFound) })
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_PATH, dest)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun uploadSendsIfMatchAndSucceeds() = runTest {
        val (s, history, verify) = storage("tok", { respond("""{"eTag":"etag2"}""", HttpStatusCode.OK, jsonHeaders) })
        val r = s.upload(CLOUD_DB_PATH, uploadSourceOf(byteArrayOf(1)), expectedRev = "etag1")
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("etag2", r.value.rev)
        assertEquals(1, history.size)
        assertEquals("PUT", history[0].method.value)
        assertTrue(history[0].url.toString().contains(":/content"))
        assertEquals("etag1", history[0].headers["If-Match"])
        verify()
    }

    @Test
    fun uploadConflictWhenIfMatchFails() = runTest {
        val (s, history, verify) = storage("tok", { respondError(HttpStatusCode.PreconditionFailed) })
        val r = s.upload(CLOUD_DB_PATH, uploadSourceOf(byteArrayOf(1)), expectedRev = "etag1")
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun uploadWithNullExpectedRevSendsNoIfMatch() = runTest {
        val (s, history, verify) = storage("tok", { respond("""{"eTag":"etag2"}""", HttpStatusCode.OK, jsonHeaders) })
        val r = s.upload(CLOUD_DB_PATH, uploadSourceOf(byteArrayOf(1)), expectedRev = null)
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals(1, history.size)
        assertEquals("PUT", history[0].method.value)
        assertNull(history[0].headers["If-Match"])
        verify()
    }

    @Test
    fun createSucceedsWithConflictBehaviorFail() = runTest {
        val (s, history, verify) = storage("tok", { respond("""{"eTag":"etag1"}""", HttpStatusCode.Created, jsonHeaders) })
        val r = s.create(CLOUD_DB_PATH, uploadSourceOf(byteArrayOf(1)))
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("etag1", r.value.rev)
        assertEquals(1, history.size)
        assertEquals("PUT", history[0].method.value)
        assertTrue(history[0].url.toString().contains("conflictBehavior"))
        verify()
    }

    @Test
    fun createConflictWhenAlreadyExists() = runTest {
        val (s, history, verify) = storage("tok", { respondError(HttpStatusCode.Conflict) })
        val r = s.create(CLOUD_DB_PATH, uploadSourceOf(byteArrayOf(1)))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun deleteRemovesFile() = runTest {
        val (s, history, verify) = storage("tok", { respond("", HttpStatusCode.NoContent) })
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(1, history.size)
        assertEquals("DELETE", history[0].method.value)
        verify()
    }

    @Test
    fun deleteMissingFileIsSuccess() = runTest {
        val (s, history, verify) = storage("tok", { respondError(HttpStatusCode.NotFound) })
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun metadataReturnsETagOrNull() = runTest {
        val (yes, _, yesVerify) = storage("tok", { respond("""{"eTag":"e7"}""", HttpStatusCode.OK, jsonHeaders) })
        assertEquals("e7", (yes.metadata(CLOUD_DB_PATH) as Result.Ok<CloudFileMeta?>).value?.rev)
        yesVerify()

        val (no, _, noVerify) = storage("tok", { respondError(HttpStatusCode.NotFound) })
        assertNull((no.metadata(CLOUD_DB_PATH) as Result.Ok<CloudFileMeta?>).value)
        noVerify()
    }

    @Test
    fun authenticateOkOn200() = runTest {
        val (s, _, verify) = storage("tok", { respond("{}", HttpStatusCode.OK, jsonHeaders) })
        assertIs<Result.Ok<Unit>>(s.authenticate())
        verify()
    }

    @Test
    fun authenticateOkWhenAppFolderNotYetCreated() = runTest {
        // 404 on approot means a valid token whose app folder has not been provisioned — still connected.
        val (s, _, verify) = storage("tok", { respondError(HttpStatusCode.NotFound) })
        assertIs<Result.Ok<Unit>>(s.authenticate())
        verify()
    }

    @Test
    fun authenticateUnauthorizedIsAuthError() = runTest {
        val (s, _, verify) = storage("tok", { respondError(HttpStatusCode.Unauthorized) })
        val r = s.authenticate()
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        verify()
    }

    @Test
    fun missingTokenIsAuthError() = runTest {
        val (s, history, verify) = storage(token = null)
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_PATH, dest)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals(0, history.size)
        verify()
    }

    @Test
    fun unauthorizedOnMetadataMapsToAuthError() = runTest {
        val (s, history, verify) = storage("tok", { respondError(HttpStatusCode.Unauthorized) })
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_PATH, dest)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun downloadNetworkExceptionIsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { throw IOException("boom") })
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_PATH, dest)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun cancellationPropagatesNotConvertedToError() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val (s, _, _) = storage(
            "tok",
            {
                started.complete(Unit)
                gate.await()
                respond(meta(), HttpStatusCode.OK, jsonHeaders)
            },
        )
        var result: Result<CloudFileMeta>? = null
        val job = launch { result = s.download(CLOUD_DB_PATH, downloadDestPath()) }
        runCurrent()
        started.await()
        job.cancel()
        job.join()
        assertNull(result)
    }

    @Test
    fun renamePatchesTheItemName() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val (s, history, verify) = storage(
            "tok",
            { request ->
                capturedPath = request.url.encodedPath
                capturedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond("{}", HttpStatusCode.OK, jsonHeaders)
            },
        )
        val r = s.rename(CLOUD_DB_PATH, "/keryx-20260811-103000.db.bak")
        assertIs<Result.Ok<Unit>>(r)
        assertEquals(1, history.size)
        assertEquals("PATCH", history[0].method.value)
        assertEquals("/v1.0/me/drive/special/approot:/keryx.db", capturedPath)
        assertEquals("""{"name":"keryx-20260811-103000.db.bak"}""", capturedBody)
        verify()
    }

    @Test
    fun renameTreatsAnAbsentItemAsSuccess() = runTest {
        val (s, _, verify) = storage("tok", { respondError(HttpStatusCode.NotFound) })
        val r = s.rename(CLOUD_DB_PATH, "/keryx-20260811-103000.db.bak")
        assertIs<Result.Ok<Unit>>(r)
        verify()
    }

    @Test
    fun renameSurfacesANameConflict() = runTest {
        val (s, _, verify) = storage("tok", { respondError(HttpStatusCode.Conflict) })
        val r = s.rename(CLOUD_DB_PATH, "/keryx-20260811-103000.db.bak")
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }
}
