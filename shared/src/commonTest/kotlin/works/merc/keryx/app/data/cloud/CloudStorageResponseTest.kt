package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `CloudStorage.kt`'s [writtenMetaOrConflictOr] — the write-response mapping every provider's
 * upload shares, so a regression in one branch would break each provider's write revision at once.
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
}
