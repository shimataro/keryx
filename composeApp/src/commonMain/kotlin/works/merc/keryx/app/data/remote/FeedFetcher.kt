package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import works.merc.keryx.app.core.FEED_TIMEOUT_RETRY_COUNT
import works.merc.keryx.app.core.FeedDiscoveryException
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.FeedParseException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.MAX_REDIRECTS
import works.merc.keryx.app.core.READ_TIMEOUT_SECONDS_DEFAULT
import works.merc.keryx.app.core.Result

/**
 * Fetches and parses a feed over HTTP. The injected [client] must be configured
 * with `followRedirects = false` and `expectSuccess = false` so this class can
 * handle status codes explicitly.
 *
 * Follows all redirect codes and guards against redirect loops ([MAX_REDIRECTS]).
 */
class FeedFetcher(
    private val client: HttpClient,
    private val readTimeoutSeconds: () -> Int = { READ_TIMEOUT_SECONDS_DEFAULT },
) {
    private val permanentRedirects = setOf(301, 308)
    private val temporaryRedirects = setOf(302, 303, 307)

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
    ): Result<FetchedFeed> {
        var attempt = 0
        while (true) {
            try {
                return doFetch(url, etag, lastModified, redirectCount = 0, permanentTarget = null)
            } catch (e: CancellationException) {
                // Coroutine cancellation is not a fetch failure — rethrow so a cancelled refresh
                // never becomes a bogus FeedFetchException/FeedTimeoutException (and never retries).
                throw e
            } catch (e: Throwable) {
                if (isTimeout(e)) {
                    if (attempt < FEED_TIMEOUT_RETRY_COUNT) {
                        attempt++
                        continue
                    }
                    return Result.Err(FeedTimeoutException())
                }
                return Result.Err(FeedFetchException(e.message ?: "Feed request failed"))
            }
        }
    }

    /**
     * Fetches and parses a feed, following redirects and preserving conditional response metadata.
     *
     * @param url The feed URL to request.
     * @param etag The previously received entity tag, if available.
     * @param lastModified The previously received last-modified value, if available.
     * @param redirectCount The number of redirects already followed.
     * @param permanentTarget The permanent redirect destination to preserve across requests.
     * @return The parsed feed, a not-modified result, or an error describing the failed fetch.
     */
    private suspend fun doFetch(
        url: String,
        etag: String?,
        lastModified: String?,
        redirectCount: Int,
        permanentTarget: String?,
    ): Result<FetchedFeed> {
        if (redirectCount > MAX_REDIRECTS) {
            return Result.Err(FeedFetchException("Too many redirects"))
        }

        val response = client.get(url) {
            etag?.let { header("If-None-Match", it) }
            lastModified?.let { header("If-Modified-Since", it) }
            timeout { requestTimeoutMillis = readTimeoutSeconds() * 1000L }
        }
        val status = response.status.value

        when (status) {
            // permanentTarget is carried through: a feed that moved permanently AND whose ETag
            // still matches answers 301 -> 304 on every poll, so dropping it here would mean the
            // subscription URL is never updated and the 301/308 notification never fires (see
            // docs/external-spec.md "Behavior on Feed URL Change / Disappearance").
            304 -> return Result.Ok(FetchedFeed(notModified = true, redirectUrl = permanentTarget))
            in permanentRedirects -> {
                val target = resolveLocation(response.headers["location"], url)
                    ?: return Result.Err(FeedFetchException("$status without Location header"))
                return doFetch(target, etag, lastModified, redirectCount + 1, permanentTarget = target)
            }
            in temporaryRedirects -> {
                val target = resolveLocation(response.headers["location"], url)
                    ?: return Result.Err(FeedFetchException("$status without Location header"))
                return doFetch(target, etag, lastModified, redirectCount + 1, permanentTarget = permanentTarget)
            }
            410 -> return Result.Err(FeedNotFoundException("Feed is gone", isGone = true))
            404 -> return Result.Err(FeedNotFoundException("Feed not found"))
        }
        if (status >= 400) {
            return Result.Err(FeedFetchException("HTTP $status", statusCode = status))
        }

        val body = response.bodyAsText()
        val parsed = FeedParser.detectAndParse(body)
            ?: run {
                val candidates = FeedDiscovery.discover(body, url)
                return if (candidates.isNotEmpty()) {
                    Result.Err(FeedDiscoveryException(candidates))
                } else {
                    Result.Err(FeedParseException("Unknown feed format"))
                }
            }

        return Result.Ok(
            parsed.copy(
                etag = response.headers["etag"],
                lastModified = response.headers["last-modified"],
                redirectUrl = permanentTarget,
            ),
        )
    }

    private fun resolveLocation(location: String?, base: String): String? {
        val loc = location?.takeIf { it.isNotBlank() } ?: return null
        return UrlResolver.resolve(base, loc) ?: loc
    }

    private fun isTimeout(e: Throwable): Boolean =
        e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is SocketTimeoutException
}
