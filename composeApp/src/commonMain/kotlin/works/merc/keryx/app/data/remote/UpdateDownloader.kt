package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MAX_REDIRECTS
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.UPDATE_DOWNLOAD_SOCKET_TIMEOUT_MS
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.platform.ContentDigest
import works.merc.keryx.app.platform.FileSystemExtras

private const val TAG = "UpdateDownloader"

/** Bytes moved per read while streaming the download to disk — see [CloudFileTransfer]'s
 * `TRANSFER_CHUNK_BYTES` for why this is a chunked copy rather than a single in-memory read. */
private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

/** Minimum bytes between [UpdateDownloader.download]'s progress callbacks. A 100+ MB asset in
 * 64 KiB chunks would otherwise invoke it thousands of times — wastefully precise for a UI that
 * only ever shows a whole-percent progress bar and a tray label rounded further still. */
internal const val UPDATE_PROGRESS_EMIT_BYTES = 256 * 1024L

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * Hosts an update asset download may land on after following a redirect from GitHub. The Releases
 * API itself answers on `github.com`/`api.github.com`; the signed asset URL it redirects to lives
 * on a `*.githubusercontent.com` subdomain (observed in practice: `release-assets.
 * githubusercontent.com`). Suffix-matched with a leading dot, so `evilgithubusercontent.com` is
 * never mistaken for a subdomain of it. Checked on every hop, including the first — not just the
 * final destination — so a compromised or malformed redirect chain is rejected as soon as it
 * leaves the allowlist rather than only once it stops moving.
 */
internal fun isAllowedUpdateDownloadHost(host: String): Boolean =
    host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")

/**
 * Minimum bytes between two progress readings for [UpdateDownloader.download] to actually invoke
 * its progress callback — see [UPDATE_PROGRESS_EMIT_BYTES]. Always emits the final reading
 * ([bytesDone] `==` [total]) so a caller driving a progress bar or an assertion never misses 100%.
 */
internal fun shouldEmitProgress(bytesDone: Long, lastEmitted: Long, total: Long): Boolean =
    bytesDone - lastEmitted >= UPDATE_PROGRESS_EMIT_BYTES || bytesDone >= total

/**
 * Downloads a single GitHub release asset to [destPath], verifying its exact size and SHA-256
 * digest before the result becomes visible there.
 *
 * Deliberately has no resume/partial-download support: a GitHub-signed asset URL expires roughly an
 * hour after it's issued, so "resuming" would still need the original request replayed from
 * scratch to get a fresh one — there is nothing to resume *against*. A failed or cancelled download
 * is simply retried in full from [url].
 *
 * The shared app [HttpClient] is configured with `followRedirects = false` (every other caller
 * handles its own redirects explicitly too — see `FeedFetcher`), so this follows redirects itself,
 * checking [isAllowedUpdateDownloadHost] on every hop and capping the chain at [MAX_REDIRECTS].
 */
class UpdateDownloader(private val client: HttpClient) {

    /**
     * @param url The asset's `browser_download_url` (or a redirect target reached from it).
     * @param destPath Final path the verified download is moved to. A `$destPath.part` sibling
     *   holds the in-progress download; its presence after a crash means a previous attempt never
     *   finished verifying, and it should be swept the same as any other stale download.
     * @param expectedSizeBytes The asset's `size` field, checked exactly (not just as an upper
     *   bound) once the download completes — short *or* long is a failure.
     * @param expectedSha256 The asset's parsed `digest` — see
     *   [works.merc.keryx.app.domain.parseSha256Digest].
     * @param onProgress Invoked as bytes arrive, throttled to roughly every
     *   [UPDATE_PROGRESS_EMIT_BYTES] — never invoked more often than that, always invoked once more
     *   at completion.
     */
    suspend fun download(
        url: String,
        destPath: String,
        expectedSizeBytes: Long,
        expectedSha256: String,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): Result<Unit> {
        val partPath = "$destPath.part"
        return try {
            val response = fetchFollowingRedirects(url, redirectCount = 0)
            if (response.status.value !in 200..299) {
                return Result.Err(UpdateException(UpdateStage.DOWNLOAD, "HTTP ${response.status.value}"))
            }

            writeVerifiedBody(response, partPath, expectedSizeBytes, onProgress)

            val actualSha256 = ContentDigest.sha256File(partPath)
            if (actualSha256 != expectedSha256) {
                FileSystemExtras.deleteRecursively(partPath)
                return Result.Err(UpdateException(UpdateStage.VERIFY, "Downloaded file's digest does not match"))
            }

            SystemFileSystem.atomicMove(Path(partPath), Path(destPath))
            Result.Ok(Unit)
        } catch (e: CancellationException) {
            FileSystemExtras.deleteRecursively(partPath)
            throw e // a cancelled download is not a failed one — the caller (UpdateRepository) treats it as such
        } catch (e: Exception) {
            FileSystemExtras.deleteRecursively(partPath)
            Log.warn(TAG, "Update download failed", e)
            Result.Err(UpdateException(UpdateStage.DOWNLOAD, e.message ?: "Download failed"))
        }
    }

    private suspend fun fetchFollowingRedirects(url: String, redirectCount: Int): HttpResponse {
        check(redirectCount <= MAX_REDIRECTS) { "Too many redirects" }
        requireAllowedDownloadUrl(url)

        val response = client.get(url) {
            header(HttpHeaders.UserAgent, APP_NAME)
            timeout {
                // The overall request has no useful upper bound for an asset this size (see this
                // class's own KDoc); only a stalled *socket* — no bytes at all for this long — is
                // actually stuck.
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = UPDATE_DOWNLOAD_SOCKET_TIMEOUT_MS
            }
        }
        if (response.status.value !in REDIRECT_STATUSES) return response

        val location = response.headers[HttpHeaders.Location]
        checkNotNull(location) { "${response.status.value} without Location header" }
        val target = UrlResolver.resolve(url, location) ?: location
        return fetchFollowingRedirects(target, redirectCount + 1)
    }

    private fun requireAllowedDownloadUrl(url: String) {
        val parsed = Url(url)
        check(parsed.protocol.name == "https") { "Refusing a non-HTTPS update download URL" }
        check(isAllowedUpdateDownloadHost(parsed.host)) { "Refusing update download from disallowed host: ${parsed.host}" }
    }

    private suspend fun writeVerifiedBody(
        response: HttpResponse,
        partPath: String,
        expectedSizeBytes: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null) {
            check(declaredLength == expectedSizeBytes) {
                "Content-Length ($declaredLength) does not match the expected size ($expectedSizeBytes)"
            }
        }

        val channel = response.bodyAsChannel()
        var total = 0L
        var lastEmitted = 0L
        SystemFileSystem.sink(Path(partPath)).buffered().use { sink ->
            while (true) {
                val packet = channel.readRemaining(DOWNLOAD_CHUNK_BYTES.toLong())
                if (packet.exhausted()) break
                val bytes = packet.readByteArray()
                total += bytes.size
                check(total <= expectedSizeBytes) { "Downloaded body exceeds the expected $expectedSizeBytes-byte size" }
                sink.write(bytes)
                if (shouldEmitProgress(total, lastEmitted, expectedSizeBytes)) {
                    lastEmitted = total
                    onProgress(total, expectedSizeBytes)
                }
            }
        }
        check(total == expectedSizeBytes) { "Downloaded $total bytes, expected $expectedSizeBytes" }
    }
}
