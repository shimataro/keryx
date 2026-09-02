package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [UpdateDownloader] tests use [runBlocking] rather than `runTest`'s virtual time — its `timeout {}`
 * overrides (see the class's own KDoc) mix badly with MockEngine's `HttpTimeout` under virtual
 * time, matching `FeedFetcherTest`'s own reasoning (docs/testing.md).
 */
class UpdateDownloaderTest {

    private fun sha256Hex(bytes: ByteArray): String =
        // b.toInt() and 0xFF avoids Byte's sign extension (a byte >= 0x80 would otherwise format
        // as 8 hex digits instead of 2) — the same pitfall ContentDigest's own hex conversion
        // exists specifically to avoid.
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    /** Bigger than one download chunk and several progress-emit thresholds, so both loops are
     * actually exercised more than once. */
    private fun multiChunkPayload(): ByteArray = Random(11).nextBytes(600 * 1024)

    private fun destFile(): File = File.createTempFile("keryx-update-dest-", ".zip").apply { delete() }

    @Test
    fun downloadsAndVerifiesAMatchingPayload() = runBlocking {
        val payload = multiChunkPayload()
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/owner/repo/releases/download/v1/asset.zip",
            destPath = dest.absolutePath,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        assertIs<Result.Ok<Unit>>(result)
        assertContentEquals(payload, dest.readBytes())
        assertFalse(File("${dest.absolutePath}.part").exists())
    }

    @Test
    fun progressCallbackIsMonotonicAndEndsAtTheTotal() = runBlocking {
        val payload = multiChunkPayload()
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { followRedirects = false; expectSuccess = false }
        val dest = destFile()
        val readings = mutableListOf<Pair<Long, Long>>()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = sha256Hex(payload),
            onProgress = { done, total -> readings.add(done to total) },
        )

        assertIs<Result.Ok<Unit>>(result)
        assertTrue(readings.isNotEmpty())
        assertTrue(readings.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertEquals(payload.size.toLong() to payload.size.toLong(), readings.last())
    }

    @Test
    fun digestMismatchFailsAndLeavesNoFileBehind() = runBlocking {
        val payload = multiChunkPayload()
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = "0".repeat(64), // definitely wrong
        )

        assertIs<Result.Err>(result)
        assertEquals(UpdateStage.VERIFY, (result.exception as UpdateException).stage)
        assertFalse(dest.exists())
        assertFalse(File("${dest.absolutePath}.part").exists())
    }

    @Test
    fun bodyExceedingTheExpectedSizeFailsAndLeavesNoFileBehind() = runBlocking {
        val payload = multiChunkPayload()
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = (payload.size - 1).toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        assertIs<Result.Err>(result)
        assertEquals(UpdateStage.DOWNLOAD, (result.exception as UpdateException).stage)
        assertFalse(File("${dest.absolutePath}.part").exists())
    }

    @Test
    fun bodyShorterThanTheExpectedSizeFails() = runBlocking {
        val payload = multiChunkPayload()
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = (payload.size + 1).toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        assertIs<Result.Err>(result)
        assertFalse(File("${dest.absolutePath}.part").exists())
    }

    @Test
    fun mismatchedContentLengthFailsBeforeAnyBytesAreWritten(): Unit = runBlocking {
        val payload = multiChunkPayload()
        val client = HttpClient(
            MockEngine { respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (payload.size + 5).toString())) },
        ) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        assertIs<Result.Err>(result)
    }

    @Test
    fun followsARedirectToAnAllowedHost() = runBlocking {
        val payload = multiChunkPayload()
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                history.add(request)
                if (request.url.host == "github.com") {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/asset.zip"),
                    )
                } else {
                    respond(payload, HttpStatusCode.OK)
                }
            },
        ) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/owner/repo/releases/download/v1/asset.zip",
            destPath = dest.absolutePath,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(2, history.size)
        assertEquals("github.com", history[0].url.host)
        assertEquals("release-assets.githubusercontent.com", history[1].url.host)
    }

    @Test
    fun refusesARedirectToADisallowedHostWithoutEverRequestingIt() = runBlocking {
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                history.add(request)
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example.com/asset.zip"))
            },
        ) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = 1L,
            expectedSha256 = "a".repeat(64),
        )

        assertIs<Result.Err>(result)
        // Only the first (allowed) request was ever made — the disallowed redirect target is
        // rejected before a second request is issued, not after receiving its response.
        assertEquals(1, history.size)
    }

    @Test
    fun refusesASimilarLookingHostThatIsNotActuallyASubdomain() = runBlocking {
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request -> history.add(request); respond("", HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
        }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://evilgithubusercontent.com/x",
            destPath = dest.absolutePath,
            expectedSizeBytes = 1L,
            expectedSha256 = "a".repeat(64),
        )

        assertIs<Result.Err>(result)
        assertEquals(0, history.size) // rejected before the very first request
    }

    @Test
    fun refusesAnHttpDownloadUrlWithoutEverRequestingIt() = runBlocking {
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request -> history.add(request); respond("", HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
        }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "http://github.com/owner/repo/releases/download/v1/asset.zip",
            destPath = dest.absolutePath,
            expectedSizeBytes = 1L,
            expectedSha256 = "a".repeat(64),
        )

        assertIs<Result.Err>(result)
        assertEquals(0, history.size)
    }

    @Test
    fun stopsFollowingRedirectsAfterTheLimit() = runBlocking {
        var requestCount = 0
        val client = HttpClient(
            MockEngine { request ->
                requestCount++
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://github.com/next-${request.url}"),
                )
            },
        ) { followRedirects = false; expectSuccess = false }
        val dest = destFile()

        val result = UpdateDownloader(client).download(
            url = "https://github.com/start",
            destPath = dest.absolutePath,
            expectedSizeBytes = 1L,
            expectedSha256 = "a".repeat(64),
        )

        assertIs<Result.Err>(result)
        assertEquals(UpdateStage.DOWNLOAD, (result.exception as UpdateException).stage)
        // MAX_REDIRECTS (5) further hops after the initial request, then it gives up.
        assertTrue(requestCount in 1..10, "expected a small bounded number of requests, got $requestCount")
    }

    // --- shouldEmitProgress ---

    @Test
    fun shouldEmitProgressIsFalseBelowTheThreshold() {
        assertFalse(shouldEmitProgress(bytesDone = 100, lastEmitted = 0, total = 1_000_000))
    }

    @Test
    fun shouldEmitProgressIsTrueAtOrAboveTheThreshold() {
        assertTrue(shouldEmitProgress(bytesDone = UPDATE_PROGRESS_EMIT_BYTES, lastEmitted = 0, total = 1_000_000))
        assertTrue(shouldEmitProgress(bytesDone = UPDATE_PROGRESS_EMIT_BYTES + 1, lastEmitted = 0, total = 1_000_000))
    }

    @Test
    fun shouldEmitProgressIsAlwaysTrueOnCompletionEvenBelowTheThreshold() {
        assertTrue(shouldEmitProgress(bytesDone = 10, lastEmitted = 5, total = 10))
    }

    // --- isAllowedUpdateDownloadHost ---

    @Test
    fun allowedHostsIncludeGitHubAndItsAssetSubdomains() {
        assertTrue(isAllowedUpdateDownloadHost("github.com"))
        assertTrue(isAllowedUpdateDownloadHost("api.github.com"))
        assertTrue(isAllowedUpdateDownloadHost("release-assets.githubusercontent.com"))
        assertTrue(isAllowedUpdateDownloadHost("objects.githubusercontent.com"))
    }

    @Test
    fun disallowedHostsIncludeLookalikesAndUnrelatedDomains() {
        assertFalse(isAllowedUpdateDownloadHost("evilgithubusercontent.com"))
        assertFalse(isAllowedUpdateDownloadHost("githubusercontent.com.evil.com"))
        assertFalse(isAllowedUpdateDownloadHost("github.com.evil.com"))
        assertFalse(isAllowedUpdateDownloadHost("evil.com"))
    }
}
