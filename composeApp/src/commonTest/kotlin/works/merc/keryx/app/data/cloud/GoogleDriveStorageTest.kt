package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
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
 * Every [GoogleDriveStorage] operation starts with a `findFile` lookup request, so
 * multi-request flows (e.g. [GoogleDriveStorage.download]) are mocked with a queue
 * of canned responses served in call order.
 */
class GoogleDriveStorageTest {

    private fun storage(
        token: String? = "tok",
        vararg responses: MockRequestHandler,
    ): Triple<GoogleDriveStorage, MutableList<HttpRequestData>, () -> Unit> {
        val history = mutableListOf<HttpRequestData>()
        val queue = ArrayDeque(responses.toList())
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            queue.removeFirst().invoke(this, request)
        }) {
            expectSuccess = false
        }
        val verify = { assertTrue(queue.isEmpty(), "Unconsumed mock response handlers remain") }
        return Triple(GoogleDriveStorage(client, accessTokenProvider = { token }), history, verify)
    }

    private fun foundFile(id: String = "F1", version: String = "r1") =
        """{"files":[{"id":"$id","version":"$version"}]}"""

    private val notFound = """{"files":[]}"""

    @Test
    fun downloadReturnsBytesAndRev() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val (s, history, verify) = storage(
            "tok",
            { respond(foundFile(version = "r42"), HttpStatusCode.OK) },
            { respond(bytes, HttpStatusCode.OK) },
        )
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Ok<CloudFile>>(r)
        assertEquals("r42", r.value.rev)
        assertContentEquals(bytes, r.value.data)
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("GET", history[1].method.value)
        assertEquals("/drive/v3/files/F1", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun downloadFileNotFoundIsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { respond(notFound, HttpStatusCode.OK) })
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun deleteRemovesExistingFile() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(foundFile(), HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
        )
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("DELETE", history[1].method.value)
        assertEquals("/drive/v3/files/F1", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun deleteReturning404AfterListIsSuccess() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(foundFile(), HttpStatusCode.OK) },
            { respondError(HttpStatusCode.NotFound) },
        )
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("DELETE", history[1].method.value)
        assertEquals("/drive/v3/files/F1", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun deleteMissingFileIsSuccessWithNoSecondRequest() = runTest {
        val (s, history, verify) = storage("tok", { respond(notFound, HttpStatusCode.OK) })
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun deleteRemovesAllMatchingFiles() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
            { respond("", HttpStatusCode.NoContent) },
        )
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(3, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("DELETE", history[1].method.value)
        assertEquals("/drive/v3/files/F1", history[1].url.encodedPath)
        assertEquals("DELETE", history[2].method.value)
        assertEquals("/drive/v3/files/F2", history[2].url.encodedPath)
        verify()
    }

    @Test
    fun deleteOneOfManyReturning404IsSuccess() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respondError(HttpStatusCode.NotFound) },
            { respond("", HttpStatusCode.NoContent) },
        )
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
        assertEquals(3, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("DELETE", history[1].method.value)
        assertEquals("/drive/v3/files/F1", history[1].url.encodedPath)
        assertEquals("DELETE", history[2].method.value)
        assertEquals("/drive/v3/files/F2", history[2].url.encodedPath)
        verify()
    }

    @Test
    fun uploadCreatesNewFileWhenNoneExists() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun uploadUpdatesExistingFileWhenRevMatches() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(foundFile(version = "r1"), HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r2"}""", HttpStatusCode.OK) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = "r1")
        assertIs<Result.Ok<Unit>>(r)
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("PATCH", history[1].method.value)
        assertEquals("/upload/drive/v3/files/F1", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun uploadConflictWhenRevMismatch() = runTest {
        val (s, history, verify) = storage("tok", { respond(foundFile(version = "r2"), HttpStatusCode.OK) })
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = "r1")
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun uploadWithNullExpectedRevOverwritesRegardlessOfExisting() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(foundFile(version = "r-whatever"), HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r-new"}""", HttpStatusCode.OK) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = null)
        assertIs<Result.Ok<Unit>>(r)
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("PATCH", history[1].method.value)
        assertEquals("/upload/drive/v3/files/F1", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun existsTrueAndFalse() = runTest {
        val (yes, yesHistory, yesVerify) = storage("tok", { respond(foundFile(), HttpStatusCode.OK) })
        assertEquals(true, (yes.exists(CLOUD_DB_PATH) as Result.Ok).value)
        assertEquals(1, yesHistory.size)
        assertEquals("GET", yesHistory[0].method.value)
        assertEquals("/drive/v3/files", yesHistory[0].url.encodedPath)
        yesVerify()

        val (no, noHistory, noVerify) = storage("tok", { respond(notFound, HttpStatusCode.OK) })
        assertEquals(false, (no.exists(CLOUD_DB_PATH) as Result.Ok).value)
        assertEquals(1, noHistory.size)
        assertEquals("GET", noHistory[0].method.value)
        assertEquals("/drive/v3/files", noHistory[0].url.encodedPath)
        noVerify()
    }

    @Test
    fun createNewFileSucceeds() = runTest {
        // A solitary post-create listing is re-checked once more before being trusted, so a
        // successful create sees the "no duplicates" listing twice.
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"}]}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"}]}""", HttpStatusCode.OK) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
        assertEquals(4, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
        assertEquals("GET", history[2].method.value)
        assertEquals("/drive/v3/files", history[2].url.encodedPath)
        assertEquals("GET", history[3].method.value)
        assertEquals("/drive/v3/files", history[3].url.encodedPath)
        verify()
    }

    @Test
    fun createRecheckConfirmsSoleWinner() = runTest {
        // Same shape as createNewFileSucceeds, but asserting explicitly that exactly one recheck
        // happens (no third listing) once the recheck also comes back solitary.
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"}]}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"}]}""", HttpStatusCode.OK) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
        // 1 findFile (create-only check) + 1 create + exactly 2 post-create listings.
        assertEquals(4, history.size)
        verify()
    }

    @Test
    fun createRecheckDetectsLateArrivingDuplicate() = runTest {
        // The first post-create listing is solitary (Drive's index hasn't caught up with a
        // concurrently racing device yet); the recheck reveals a lower-id duplicate, so this
        // device must recognize it lost the race and clean up its own file.
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F2","version":"r2"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(5, history.size)
        assertEquals("DELETE", history[4].method.value)
        assertEquals("/drive/v3/files/F2", history[4].url.encodedPath)
        verify()
    }

    @Test
    fun createReturnsConflictWhenAlreadyExists() = runTest {
        val (s, history, verify) = storage("tok", { respond(foundFile(), HttpStatusCode.OK) })
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun createReconcilesRacingDuplicates() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
        assertEquals(4, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
        assertEquals("GET", history[2].method.value)
        assertEquals("/drive/v3/files", history[2].url.encodedPath)
        assertEquals("DELETE", history[3].method.value)
        assertEquals("/drive/v3/files/F2", history[3].url.encodedPath)
        verify()
    }

    @Test
    fun createReturnsConflictWhenNotTheDeterministicWinner() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F2","version":"r2"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        // The loser deletes its own just-created file (F2) so it does not linger as an orphan,
        // then reports the conflict.
        assertEquals(4, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
        assertEquals("GET", history[2].method.value)
        assertEquals("/drive/v3/files", history[2].url.encodedPath)
        assertEquals("DELETE", history[3].method.value)
        assertEquals("/drive/v3/files/F2", history[3].url.encodedPath)
        verify()
    }

    @Test
    fun createLoserStillReportsConflictWhenOwnCleanupFails() = runTest {
        // Even if deleting our own orphan fails, the conflict signal must survive so the sync flow
        // reconverges (a failed best-effort cleanup must not mask the race outcome).
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F2","version":"r2"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(4, history.size)
        assertEquals("DELETE", history[3].method.value)
        assertEquals("/drive/v3/files/F2", history[3].url.encodedPath)
        verify()
    }

    @Test
    fun createPropagatesPostCreateListError() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(3, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
        assertEquals("GET", history[2].method.value)
        assertEquals("/drive/v3/files", history[2].url.encodedPath)
        verify()
    }

    @Test
    fun createPropagatesPostCreateDeleteError() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(4, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
        assertEquals("GET", history[2].method.value)
        assertEquals("/drive/v3/files", history[2].url.encodedPath)
        assertEquals("DELETE", history[3].method.value)
        assertEquals("/drive/v3/files/F2", history[3].url.encodedPath)
        verify()
    }

    @Test
    fun missingTokenIsAuthError() = runTest {
        val (s, history, verify) = storage(token = null)
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals(0, history.size)
        verify()
    }

    @Test
    fun findFileUnauthorizedMapsToAuthError() = runTest {
        val (s, history, verify) = storage("tok", { respondError(HttpStatusCode.Unauthorized) })
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun findFileMissingFilesArrayIsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { respond("{}", HttpStatusCode.OK) })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("Missing files array in response", r.exception.message)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun findFileMissingIdIsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { respond("""{"files":[{"version":"r1"}]}""", HttpStatusCode.OK) })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("File metadata missing id", r.exception.message)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun findFileMissingVersionIsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { respond("""{"files":[{"id":"F1"}]}""", HttpStatusCode.OK) })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("File metadata missing version", r.exception.message)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun downloadContentFetchServerErrorMapsToCloudStorageError() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(foundFile(), HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("GET", history[1].method.value)
        assertEquals("/drive/v3/files/F1", history[1].url.encodedPath)
        verify()
    }

    @Test
    fun uploadServerErrorOnCreateMapsToCloudStorageError() = runTest {
        val (s, history, verify) = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(2, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        assertEquals("POST", history[1].method.value)
        assertEquals("/upload/drive/v3/files", history[1].url.encodedPath)
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
                respond(foundFile(version = "r42"), HttpStatusCode.OK)
            },
        )
        var result: Result<CloudFile>? = null
        val job = launch { result = s.download(CLOUD_DB_PATH) }
        runCurrent()
        started.await()
        job.cancel()
        job.join()
        assertNull(result)
    }

    @Test
    fun downloadNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { throw IOException("boom") })
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun uploadNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { throw IOException("boom") })
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }

    @Test
    fun existsNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val (s, history, verify) = storage("tok", { throw IOException("boom") })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        assertEquals("GET", history[0].method.value)
        assertEquals("/drive/v3/files", history[0].url.encodedPath)
        verify()
    }
}
