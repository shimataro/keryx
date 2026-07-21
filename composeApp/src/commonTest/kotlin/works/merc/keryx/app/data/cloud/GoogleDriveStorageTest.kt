package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
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

/**
 * Every [GoogleDriveStorage] operation starts with a `findFile` lookup request, so
 * multi-request flows (e.g. [GoogleDriveStorage.download]) are mocked with a queue
 * of canned responses served in call order.
 */
class GoogleDriveStorageTest {

    private fun storage(token: String? = "tok", vararg responses: MockRequestHandler): GoogleDriveStorage {
        val queue = ArrayDeque(responses.toList())
        val client = HttpClient(MockEngine { request -> queue.removeFirst().invoke(this, request) }) {
            expectSuccess = false
        }
        return GoogleDriveStorage(client, accessTokenProvider = { token })
    }

    private fun foundFile(id: String = "F1", version: String = "r1") =
        """{"files":[{"id":"$id","version":"$version"}]}"""

    private val notFound = """{"files":[]}"""

    @Test
    fun downloadReturnsBytesAndRev() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val s = storage(
            "tok",
            { respond(foundFile(version = "r42"), HttpStatusCode.OK) },
            { respond(bytes, HttpStatusCode.OK) },
        )
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Ok<CloudFile>>(r)
        assertEquals("r42", r.value.rev)
        assertContentEquals(bytes, r.value.data)
    }

    @Test
    fun downloadFileNotFoundIsCloudStorageError() = runTest {
        val s = storage("tok", { respond(notFound, HttpStatusCode.OK) })
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun deleteRemovesExistingFile() = runTest {
        val s = storage(
            "tok",
            { respond(foundFile(), HttpStatusCode.OK) },     // listFilesByName
            { respond("", HttpStatusCode.NoContent) },        // DELETE → 204
        )
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
    }

    @Test
    fun deleteMissingFileIsSuccessWithNoSecondRequest() = runTest {
        // Only one queued response: listFilesByName returns empty, so no DELETE is issued.
        val s = storage("tok", { respond(notFound, HttpStatusCode.OK) })
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
    }

    @Test
    fun deleteRemovesAllMatchingFiles() = runTest {
        val s = storage(
            "tok",
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
            { respond("", HttpStatusCode.NoContent) },
        )
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_PATH))
    }

    @Test
    fun uploadCreatesNewFileWhenNoneExists() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
    }

    @Test
    fun uploadUpdatesExistingFileWhenRevMatches() = runTest {
        val s = storage(
            "tok",
            { respond(foundFile(version = "r1"), HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r2"}""", HttpStatusCode.OK) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = "r1")
        assertIs<Result.Ok<Unit>>(r)
    }

    @Test
    fun uploadConflictWhenRevMismatch() = runTest {
        val s = storage("tok", { respond(foundFile(version = "r2"), HttpStatusCode.OK) })
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = "r1")
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
    }

    @Test
    fun uploadWithNullExpectedRevOverwritesRegardlessOfExisting() = runTest {
        val s = storage(
            "tok",
            { respond(foundFile(version = "r-whatever"), HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r-new"}""", HttpStatusCode.OK) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1), expectedRev = null)
        assertIs<Result.Ok<Unit>>(r)
    }

    @Test
    fun existsTrueAndFalse() = runTest {
        val yes = storage("tok", { respond(foundFile(), HttpStatusCode.OK) })
        assertEquals(true, (yes.exists(CLOUD_DB_PATH) as Result.Ok).value)

        val no = storage("tok", { respond(notFound, HttpStatusCode.OK) })
        assertEquals(false, (no.exists(CLOUD_DB_PATH) as Result.Ok).value)
    }

    @Test
    fun createNewFileSucceeds() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"}]}""", HttpStatusCode.OK) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
    }

    @Test
    fun createReturnsConflictWhenAlreadyExists() = runTest {
        val s = storage("tok", { respond(foundFile(), HttpStatusCode.OK) })
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
    }

    @Test
    fun createReconcilesRacingDuplicates() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respond("", HttpStatusCode.NoContent) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Ok<Unit>>(r)
    }

    @Test
    fun createReturnsConflictWhenNotTheDeterministicWinner() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },                                      // findFile / listFilesByName
            { respond("""{"id":"F2","version":"r2"}""", HttpStatusCode.OK) },             // createFile
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) }, // listFiles (post-create)
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
    }

    @Test
    fun createPropagatesPostCreateListError() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun createPropagatesPostCreateDeleteError() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respond("""{"id":"F1","version":"r1"}""", HttpStatusCode.OK) },
            { respond("""{"files":[{"id":"F1","version":"r1"},{"id":"F2","version":"r2"}]}""", HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.create(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun missingTokenIsAuthError() = runTest {
        val s = storage(token = null)
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun findFileUnauthorizedMapsToAuthError() = runTest {
        val s = storage("tok", { respondError(HttpStatusCode.Unauthorized) })
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
    }

    @Test
    fun findFileMissingFilesArrayIsCloudStorageError() = runTest {
        val s = storage("tok", { respond("{}", HttpStatusCode.OK) })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("Missing files array in response", r.exception.message)
    }

    @Test
    fun findFileMissingIdIsCloudStorageError() = runTest {
        val s = storage("tok", { respond("""{"files":[{"version":"r1"}]}""", HttpStatusCode.OK) })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("File metadata missing id", r.exception.message)
    }

    @Test
    fun findFileMissingVersionIsCloudStorageError() = runTest {
        val s = storage("tok", { respond("""{"files":[{"id":"F1"}]}""", HttpStatusCode.OK) })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("File metadata missing version", r.exception.message)
    }

    @Test
    fun downloadContentFetchServerErrorMapsToCloudStorageError() = runTest {
        val s = storage(
            "tok",
            { respond(foundFile(), HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun uploadServerErrorOnCreateMapsToCloudStorageError() = runTest {
        val s = storage(
            "tok",
            { respond(notFound, HttpStatusCode.OK) },
            { respondError(HttpStatusCode.InternalServerError) },
        )
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun downloadNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val s = storage("tok", { throw IOException("boom") })
        val r = s.download(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun uploadNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val s = storage("tok", { throw IOException("boom") })
        val r = s.upload(CLOUD_DB_PATH, byteArrayOf(1))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }

    @Test
    fun existsNetworkExceptionIsCaughtAsCloudStorageError() = runTest {
        val s = storage("tok", { throw IOException("boom") })
        val r = s.exists(CLOUD_DB_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
    }
}
