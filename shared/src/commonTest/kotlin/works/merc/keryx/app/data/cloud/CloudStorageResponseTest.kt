package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `CloudStorage.kt`'s [writtenMetaOrConflictOr] and [headerRevOrConflictOr] — the write-response
 * mappings the providers' uploads share, so a regression in one branch would break each provider's write revision at once.
 */
class CloudStorageResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun writeResponse(status: HttpStatusCode, body: String): Result<CloudFileMeta> {
        val client = HttpClient(MockEngine { respond(body, status) }) { expectSuccess = false }
        return client.get("https://example.test/upload")
            .writtenMetaOrConflictOr(json, "TestProvider", "rev", conflictStatus = 409)
    }

    @Test
    fun successReturnsTheWrittenRevision() = runTest {
        val r = writeResponse(HttpStatusCode.OK, """{"rev":"r42","name":"keryx.db"}""")
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("r42", r.value.rev)
    }

    @Test
    fun successWithoutTheRevisionKeyIsAStorageError() = runTest {
        val r = writeResponse(HttpStatusCode.OK, """{"name":"keryx.db"}""")
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("Missing rev in upload response", r.exception.message)
    }

    @Test
    fun conflictStatusIsASyncConflict() = runTest {
        val r = writeResponse(HttpStatusCode.Conflict, "{}")
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
    }

    @Test
    fun otherErrorIsAStorageErrorNamingTheProvider() = runTest {
        val r = writeResponse(HttpStatusCode.InternalServerError, "boom")
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertTrue(r.exception.messageText.contains("TestProvider"), r.exception.messageText)
    }

    private suspend fun headerWriteResponse(status: HttpStatusCode, headers: Headers): Result<CloudFileMeta> {
        val client = HttpClient(MockEngine { respond("", status, headers) }) { expectSuccess = false }
        return client.get("https://example.test/upload")
            .headerRevOrConflictOr("TestProvider", listOf("OC-ETag", "ETag"), conflictStatuses = setOf(412, 423))
    }

    @Test
    fun headerRevReadsTheFirstHeaderPresentInOrder() = runTest {
        val both = headerWriteResponse(HttpStatusCode.Created, headersOf("OC-ETag" to listOf("\"oc\""), "ETag" to listOf("\"e\"")))
        assertIs<Result.Ok<CloudFileMeta>>(both)
        assertEquals("\"oc\"", both.value.rev)
        val etagOnly = headerWriteResponse(HttpStatusCode.NoContent, headersOf("ETag", "\"e\""))
        assertIs<Result.Ok<CloudFileMeta>>(etagOnly)
        assertEquals("\"e\"", etagOnly.value.rev)
    }

    @Test
    fun headerRevWithoutAnyRevHeaderIsAStorageError() = runTest {
        val r = headerWriteResponse(HttpStatusCode.Created, headersOf())
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals("Missing ETag in upload response", r.exception.message)
    }

    @Test
    fun headerRevConflictStatusesAreSyncConflicts() = runTest {
        for (status in listOf(HttpStatusCode.PreconditionFailed, HttpStatusCode.Locked)) {
            val r = headerWriteResponse(status, headersOf())
            assertIs<Result.Err>(r)
            assertIs<SyncConflictException>(r.exception, status.toString())
        }
    }

    @Test
    fun headerRevOtherErrorIsMappedLikeAnyCloudError() = runTest {
        val auth = headerWriteResponse(HttpStatusCode.Unauthorized, headersOf())
        assertIs<Result.Err>(auth)
        assertIs<CloudAuthException>(auth.exception)
        val storage = headerWriteResponse(HttpStatusCode.InsufficientStorage, headersOf())
        assertIs<Result.Err>(storage)
        assertIs<CloudStorageException>(storage.exception)
    }
}
