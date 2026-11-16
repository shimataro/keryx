package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.CLOUD_DB_GZ_PATH
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.CloudCredentials
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.bytesAt
import works.merc.keryx.app.data.cloud.downloadDestPath
import works.merc.keryx.app.data.cloud.uploadSourceOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NextcloudStorageTest {

    private val credentials = AppPasswordCredentials(
        serverUrl = "https://cloud.example.com/nc",
        userId = "alice smith",
        loginName = "alice@example.com",
        appPassword = "app-pass",
    )

    private val base = "https://cloud.example.com/nc/remote.php/dav/files/alice%20smith"
    private val dbUrl = "$base/Keryx/keryx.db.gz"

    private fun storage(
        creds: CloudCredentials? = credentials,
        vararg responses: MockRequestHandler,
    ): Triple<NextcloudStorage, MutableList<HttpRequestData>, () -> Unit> {
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
        return Triple(NextcloudStorage(client, credentialsProvider = { creds }, newTempName = { ".upload-1.tmp" }), history, verify)
    }

    private fun multistatus(prefix: String, etag: String) = """
        <?xml version="1.0"?>
        <$prefix:multistatus xmlns:$prefix="DAV:" xmlns:oc="http://owncloud.org/ns">
          <$prefix:response>
            <$prefix:href>/nc/remote.php/dav/files/alice%20smith/Keryx/keryx.db.gz</$prefix:href>
            <$prefix:propstat>
              <$prefix:prop><$prefix:getetag>&quot;$etag&quot;</$prefix:getetag></$prefix:prop>
              <$prefix:status>HTTP/1.1 200 OK</$prefix:status>
            </$prefix:propstat>
          </$prefix:response>
        </$prefix:multistatus>
    """.trimIndent()

    private val multiStatus = HttpStatusCode(207, "Multi-Status")

    private fun HttpRequestData.header(name: String): String? = headers[name]

    @Test
    fun everyRequestCarriesBasicAuthAndAUserAgent() = runTest {
        val (s, history, verify) = storage(credentials, { respond("", multiStatus) })
        assertIs<Result.Ok<Unit>>(s.authenticate())
        val request = history.single()
        assertEquals("PROPFIND", request.method.value)
        assertEquals("$base/", request.url.toString())
        assertEquals("0", request.header("Depth"))
        // Basic base64("alice@example.com:app-pass")
        assertEquals("Basic YWxpY2VAZXhhbXBsZS5jb206YXBwLXBhc3M=", request.header(HttpHeaders.Authorization))
        assertTrue(request.header(HttpHeaders.UserAgent).orEmpty().isNotEmpty())
        verify()
    }

    @Test
    fun authenticate401IsAuthError() = runTest {
        val (s, _, verify) = storage(credentials, { respond("", HttpStatusCode.Unauthorized) })
        val r = s.authenticate()
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        verify()
    }

    @Test
    fun metadataReadsTheEtagWhateverTheNamespacePrefix() = runTest {
        for (prefix in listOf("d", "D")) {
            val (s, history, verify) = storage(credentials, { respond(multistatus(prefix, "abc123"), multiStatus) })
            val r = s.metadata(CLOUD_DB_GZ_PATH)
            assertIs<Result.Ok<CloudFileMeta?>>(r)
            assertEquals("\"abc123\"", r.value?.rev, "prefix $prefix")
            assertEquals(dbUrl, history.single().url.toString())
            assertEquals("PROPFIND", history.single().method.value)
            verify()
        }
    }

    @Test
    fun metadata404IsAbsent() = runTest {
        val (s, _, verify) = storage(credentials, { respond("", HttpStatusCode.NotFound) })
        val r = s.metadata(CLOUD_DB_GZ_PATH)
        assertIs<Result.Ok<CloudFileMeta?>>(r)
        assertNull(r.value)
        verify()
    }

    @Test
    fun metadataWithoutAnEtagIsStorageError() = runTest {
        val (s, _, verify) = storage(credentials, { respond("<d:multistatus xmlns:d=\"DAV:\"/>", multiStatus) })
        val r = s.metadata(CLOUD_DB_GZ_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }

    @Test
    fun aRedirectIsAStorageErrorAndIsNotFollowed() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "http://elsewhere/")) },
        )
        val r = s.metadata(CLOUD_DB_GZ_PATH)
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun downloadStreamsTheBodyAndReturnsItsEtag() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val (s, history, verify) = storage(
            credentials,
            { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"e1\"")) },
        )
        val dest = downloadDestPath()
        val r = s.download(CLOUD_DB_GZ_PATH, dest)
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("\"e1\"", r.value.rev)
        assertContentEquals(bytes, bytesAt(dest))
        assertEquals("GET", history.single().method.value)
        assertEquals(dbUrl, history.single().url.toString())
        verify()
    }

    @Test
    fun download404IsStorageError() = runTest {
        val (s, _, verify) = storage(credentials, { respond("", HttpStatusCode.NotFound) })
        val r = s.download(CLOUD_DB_GZ_PATH, downloadDestPath())
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }

    @Test
    fun downloadWithoutAnEtagIsStorageError() = runTest {
        val (s, _, verify) = storage(credentials, { respond(byteArrayOf(1), HttpStatusCode.OK) })
        val r = s.download(CLOUD_DB_GZ_PATH, downloadDestPath())
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }

    @Test
    fun uploadPassesTheExpectedRevThroughAsIfMatchAndPrefersOcEtag() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.NoContent, headersOf("OC-ETag" to listOf("\"oc2\""), HttpHeaders.ETag to listOf("\"e2\""))) },
        )
        val r = s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(5)), expectedRev = "\"e1\"")
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("\"oc2\"", r.value.rev)
        val request = history.single()
        assertEquals("PUT", request.method.value)
        assertEquals(dbUrl, request.url.toString())
        assertEquals("\"e1\"", request.header(HttpHeaders.IfMatch))
        verify()
    }

    @Test
    fun uploadFallsBackToEtagAndOmitsIfMatchWithoutAnExpectedRev() = runTest {
        val (s, history, verify) = storage(credentials, { respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"e3\"")) })
        val r = s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(5)))
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("\"e3\"", r.value.rev)
        assertNull(history.single().header(HttpHeaders.IfMatch))
        verify()
    }

    @Test
    fun uploadWithoutAnEtagIsStorageError() = runTest {
        val (s, _, verify) = storage(credentials, { respond("", HttpStatusCode.NoContent) })
        val r = s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(5)), expectedRev = "\"e1\"")
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }

    @Test
    fun uploadPreconditionFailedAndLockedAreConflicts() = runTest {
        for (status in listOf(HttpStatusCode.PreconditionFailed, HttpStatusCode.Locked)) {
            val (s, _, verify) = storage(credentials, { respond("", status) })
            val r = s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(5)), expectedRev = "\"e1\"")
            assertIs<Result.Err>(r)
            assertIs<SyncConflictException>(r.exception, "status $status")
            verify()
        }
    }

    @Test
    fun uploadInsufficientStorageIsStorageError() = runTest {
        val (s, _, verify) = storage(credentials, { respond("", HttpStatusCode.InsufficientStorage) })
        val r = s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(5)), expectedRev = "\"e1\"")
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }

    @Test
    fun createMakesTheFolderUploadsToATempNameAndMovesItIntoPlace() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.MethodNotAllowed) }, // MKCOL: folder exists
            { respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"tmp\"")) }, // PUT temp
            { respond("", HttpStatusCode.Created, headersOf("OC-ETag", "\"final\"")) }, // MOVE
        )
        val r = s.create(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(7)))
        assertIs<Result.Ok<CloudFileMeta>>(r)
        assertEquals("\"final\"", r.value.rev)
        assertEquals(listOf("MKCOL", "PUT", "MOVE"), history.map { it.method.value })
        assertEquals("$base/Keryx", history[0].url.toString())
        assertEquals("$base/Keryx/.upload-1.tmp", history[1].url.toString())
        assertNull(history[1].header(HttpHeaders.IfMatch))
        assertEquals("$base/Keryx/.upload-1.tmp", history[2].url.toString())
        assertEquals(dbUrl, history[2].header("Destination"))
        assertEquals("F", history[2].header("Overwrite"))
        verify()
    }

    @Test
    fun createOnAnExistingFileIsAConflictAndRemovesTheTempFile() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.Created) }, // MKCOL: folder created
            { respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"tmp\"")) },
            { respond("", HttpStatusCode.PreconditionFailed) }, // MOVE: destination exists
            { respond("", HttpStatusCode.NoContent) }, // DELETE temp
        )
        val r = s.create(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(7)))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(listOf("MKCOL", "PUT", "MOVE", "DELETE"), history.map { it.method.value })
        assertEquals("$base/Keryx/.upload-1.tmp", history[3].url.toString())
        verify()
    }

    @Test
    fun createOntoALockedDestinationIsAConflictAndRemovesTheTempFile() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.Created) },
            { respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"tmp\"")) },
            { respond("", HttpStatusCode.Locked) }, // MOVE: destination locked
            { respond("", HttpStatusCode.NoContent) }, // DELETE temp
        )
        val r = s.create(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(7)))
        assertIs<Result.Err>(r)
        assertIs<SyncConflictException>(r.exception)
        assertEquals(listOf("MKCOL", "PUT", "MOVE", "DELETE"), history.map { it.method.value })
        verify()
    }

    @Test
    fun createRemovesTheTempFileWhenTheUploadFails() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.Created) },
            { respond("", HttpStatusCode.InsufficientStorage) },
            { respond("", HttpStatusCode.NotFound) }, // DELETE temp: nothing was written
        )
        val r = s.create(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(7)))
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        assertEquals(listOf("MKCOL", "PUT", "DELETE"), history.map { it.method.value })
        verify()
    }

    @Test
    fun createRemovesTheTempFileWhenTheUploadThrows() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.Created) },
            { throw kotlinx.io.IOException("connection reset") }, // PUT
            { respond("", HttpStatusCode.NoContent) }, // DELETE temp
        )
        // On a real dispatcher: the cleanup's withTimeoutOrNull would otherwise run on runTest's
        // virtual clock, which skips straight past the timeout while the engine answers on another thread.
        withContext(Dispatchers.Default) {
            runCatching { s.create(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(7))) }
        }
        assertEquals(listOf("MKCOL", "PUT", "DELETE"), history.map { it.method.value })
        assertEquals("$base/Keryx/.upload-1.tmp", history[2].url.toString())
        verify()
    }

    @Test
    fun createStopsWhenTheFolderCannotBeMade() = runTest {
        val (s, history, verify) = storage(credentials, { respond("", HttpStatusCode.Forbidden) })
        val r = s.create(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(7)))
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertEquals(1, history.size)
        verify()
    }

    @Test
    fun deleteIsIdempotent() = runTest {
        val (s, history, verify) = storage(credentials, { respond("", HttpStatusCode.NotFound) })
        assertIs<Result.Ok<Unit>>(s.delete(CLOUD_DB_GZ_PATH))
        assertEquals("DELETE", history.single().method.value)
        assertEquals(dbUrl, history.single().url.toString())
        verify()
    }

    @Test
    fun renameMovesWithoutOverwritingAndAMissingSourceIsDone() = runTest {
        val (s, history, verify) = storage(
            credentials,
            { respond("", HttpStatusCode.Created) },
            { respond("", HttpStatusCode.NotFound) },
        )
        assertIs<Result.Ok<Unit>>(s.rename(CLOUD_DB_GZ_PATH, "/keryx-20260811-103000.db.gz.bak"))
        val request = history[0]
        assertEquals("MOVE", request.method.value)
        assertEquals(dbUrl, request.url.toString())
        assertEquals("$base/Keryx/keryx-20260811-103000.db.gz.bak", request.header("Destination"))
        assertEquals("F", request.header("Overwrite"))
        assertIs<Result.Ok<Unit>>(s.rename(CLOUD_DB_GZ_PATH, "/keryx-20260811-103000.db.gz.bak"))
        verify()
    }

    @Test
    fun renameOntoAnExistingFileIsAStorageError() = runTest {
        // A storage error (not a conflict) is what makes archiveCloudDb fall back to a delete.
        val (s, _, verify) = storage(credentials, { respond("", HttpStatusCode.PreconditionFailed) })
        val r = s.rename(CLOUD_DB_GZ_PATH, "/keryx-20260811-103000.db.gz.bak")
        assertIs<Result.Err>(r)
        assertIs<CloudStorageException>(r.exception)
        verify()
    }

    @Test
    fun missingOrNonAppPasswordCredentialsAreAuthErrorsWithoutARequest() = runTest {
        for (creds in listOf(null, OAuthTokens(accessToken = "tok"))) {
            val (s, history, _) = storage(creds)
            val r = s.metadata(CLOUD_DB_GZ_PATH)
            assertIs<Result.Err>(r)
            assertIs<CloudAuthException>(r.exception)
            assertTrue(history.isEmpty())
        }
    }

    @Test
    fun aNonHttpsServerIsAnAuthErrorWithoutARequest() = runTest {
        val (s, history, _) = storage(credentials.copy(serverUrl = "http://cloud.example.com"))
        val r = s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(1)))
        assertIs<Result.Err>(r)
        assertIs<CloudAuthException>(r.exception)
        assertTrue(history.isEmpty())
    }

    @Test
    fun cancellationPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val (s, _, _) = storage(credentials, {
            started.complete(Unit)
            never.await()
            respond("", HttpStatusCode.OK)
        })
        var result: Result<CloudFileMeta?>? = null
        val job = launch { result = s.metadata(CLOUD_DB_GZ_PATH) }
        runCurrent()
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(result)
    }

    @Test
    fun uploadStreamsTheFile() = runTest {
        val (s, history, verify) = storage(credentials, { respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, "\"e\"")) })
        s.upload(CLOUD_DB_GZ_PATH, uploadSourceOf(byteArrayOf(1, 2)), expectedRev = "\"x\"")
        assertIs<OutgoingContent.WriteChannelContent>(history.single().body)
        verify()
    }
}
