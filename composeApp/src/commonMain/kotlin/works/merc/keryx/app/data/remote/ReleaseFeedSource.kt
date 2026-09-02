package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage

private const val TAG = "ReleaseFeedSource"

/**
 * One entry of a GitHub release's `assets[]` array, as returned by the Releases API. `digest` and
 * `state` were added to that API after some already-published releases, so both are nullable —
 * [works.merc.keryx.app.domain.selectUpdateAsset] treats a missing digest the same as an
 * unverifiable one (never selected).
 */
internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val digest: String?,
    val state: String?,
)

/**
 * One GitHub release, as much of it as [works.merc.keryx.app.domain.UpdateChecker]'s candidate
 * policy and [works.merc.keryx.app.domain.UpdateStatus.Available] need — everything else in the raw
 * API response is dropped here rather than carried as an opaque `JsonObject` into `domain/`.
 *
 * @param tagName Raw `tag_name`, still carrying any leading `v`/`V` — [works.merc.keryx.app.domain.UpdateChecker]
 *   strips that. `null` when the field is missing, which [works.merc.keryx.app.domain.UpdateChecker]
 *   treats as this candidate having no usable version at all.
 * @param htmlUrl The release page (`html_url`), or `null` when missing.
 * @param body The release's Markdown body (`body`), or `null` when GitHub didn't return one.
 * @param assets The release's `assets[]`, already parsed into [ReleaseAsset]s — an entry missing a
 *   required field is skipped rather than failing the whole release.
 */
internal data class ReleaseInfo(
    val tagName: String?,
    val htmlUrl: String?,
    val prerelease: Boolean,
    val draft: Boolean,
    val body: String?,
    val assets: List<ReleaseAsset>,
)

/**
 * Fetches release metadata from a GitHub repo's Releases API — the raw HTTP request and JSON
 * parsing only. [works.merc.keryx.app.domain.UpdateChecker] owns everything that comes after: which
 * candidate to pick, whether it's actually newer, and what to do with its asset.
 */
internal class ReleaseFeedSource(
    private val client: HttpClient,
    private val currentVersion: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    // Which single endpoint gets called is fixed for this instance's whole lifetime
    // (UpdateChecker picks one based on isBelowStable(currentVersion), never both), so one cache
    // slot per method is enough — no eviction/keying by repoSlug needed. In-memory only, not
    // persisted to local_settings.json: this instance already lives for the app's whole process
    // lifetime as part of the UpdateChecker Koin single, so it already covers the case that
    // actually matters (skipping a re-fetch/re-parse on each periodic background re-check) without
    // the added complexity and surface area of persisting release payloads across restarts for the
    // comparatively rare case of two checks happening to straddle one. `cacheMutex` guards both
    // slots against the (narrow, but real) case of two check() calls overlapping — see
    // UpdateRepository.check()'s own KDoc for why the network call itself isn't otherwise
    // serialized.
    private val cacheMutex = Mutex()
    private var cachedReleaseList: ETagCache? = null
    private var cachedLatestRelease: ETagCache? = null

    /**
     * Fetches the full `releases` list (pre-stable path — see [works.merc.keryx.app.domain.UpdateChecker]'s own KDoc for why).
     * `per_page=100` raises the single-page limit from GitHub's default 30 so realistic release
     * histories fit in one request. Converts every failure mode — HTTP error, unexpected body, a
     * network exception — into [UpdateException]`(`[UpdateStage.CHECK]`, …)` here rather than
     * leaking it upward, per this codebase's error-design convention for the DataSource layer.
     */
    suspend fun fetchReleaseList(repoSlug: String): Result<List<ReleaseInfo>> = fetchReleases {
        val cached = cacheMutex.withLock { cachedReleaseList }
        val response = client.get("https://api.github.com/repos/$repoSlug/releases?per_page=100") {
            applyGitHubHeaders()
            applyConditionalHeader(cached)
        }
        if (response.status.value == 304 && cached != null) {
            return@fetchReleases Result.Ok(cached.releases)
        }
        if (response.status.value !in 200..299) {
            return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "HTTP ${response.status.value}"))
        }
        val array = json.parseToJsonElement(response.bodyAsText()) as? JsonArray
            ?: return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "Unexpected response body"))
        val releases = array.mapNotNull { (it as? JsonObject)?.let(::releaseInfoOf) }
        cacheMutex.withLock { cachedReleaseList = response.newETagCache(releases) }
        Result.Ok(releases)
    }

    /**
     * Fetches the newest full release via `releases/latest` (stable path — see [works.merc.keryx.app.domain.UpdateChecker]'s
     * own KDoc for why). Resolves to a single-element list, or an empty one on 404 (no full release
     * yet — the caller treats that as up to date, not a failure). Every other failure mode is
     * converted the same way [fetchReleaseList] converts its own.
     */
    suspend fun fetchLatestRelease(repoSlug: String): Result<List<ReleaseInfo>> = fetchReleases {
        val cached = cacheMutex.withLock { cachedLatestRelease }
        val response = client.get("https://api.github.com/repos/$repoSlug/releases/latest") {
            applyGitHubHeaders()
            applyConditionalHeader(cached)
        }
        if (response.status.value == 304 && cached != null) {
            return@fetchReleases Result.Ok(cached.releases)
        }
        if (response.status.value == 404) return@fetchReleases Result.Ok(emptyList())
        if (response.status.value !in 200..299) {
            return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "HTTP ${response.status.value}"))
        }
        val obj = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "Unexpected response body"))
        val releases = listOf(releaseInfoOf(obj))
        cacheMutex.withLock { cachedLatestRelease = response.newETagCache(releases) }
        Result.Ok(releases)
    }

    /** Shares the network-exception handling both fetch methods need: a [CancellationException]
     * must never be swallowed as a failed check, and anything else (a timeout, DNS failure, …)
     * becomes the same [UpdateException] shape their own explicit failure branches already return,
     * rather than propagating as a raw exception for [works.merc.keryx.app.domain.UpdateChecker]'s
     * own catch-all to reclassify less precisely. */
    private suspend inline fun fetchReleases(block: () -> Result<List<ReleaseInfo>>): Result<List<ReleaseInfo>> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warn(TAG, "Fetching releases failed", e)
            Result.Err(UpdateException(UpdateStage.CHECK, e.message ?: "Fetching releases failed"))
        }

    private fun releaseInfoOf(release: JsonObject): ReleaseInfo =
        ReleaseInfo(
            tagName = release["tag_name"]?.jsonPrimitive?.contentOrNull,
            htmlUrl = release["html_url"]?.jsonPrimitive?.contentOrNull,
            prerelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
            draft = release["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
            body = release["body"]?.jsonPrimitive?.contentOrNull,
            assets = releaseAssetsOf(release),
        )

    /** Parses the release's `assets[]` array into [ReleaseAsset]s, skipping any entry missing a
     * required field rather than failing the whole release over one malformed asset. */
    private fun releaseAssetsOf(release: JsonObject): List<ReleaseAsset> {
        val array = release["assets"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val digest = obj["digest"]?.jsonPrimitive?.contentOrNull
            val state = obj["state"]?.jsonPrimitive?.contentOrNull
            ReleaseAsset(name, url, size, digest, state)
        }
    }

    private fun HttpRequestBuilder.applyGitHubHeaders() {
        // GitHub's API rejects requests with no User-Agent (403) — Ktor doesn't send one by
        // default, so this must be set explicitly.
        header(HttpHeaders.UserAgent, "$APP_NAME/$currentVersion")
        header(HttpHeaders.Accept, "application/vnd.github+json")
    }

    /** Sends the cached validator, if any, so an unchanged release list/latest release costs GitHub
     * a bodyless 304 rather than the full response — and, per GitHub's own documented behavior,
     * doesn't count against the unauthenticated rate limit the way a normal request would. */
    private fun HttpRequestBuilder.applyConditionalHeader(cached: ETagCache?) {
        if (cached != null) header(HttpHeaders.IfNoneMatch, cached.etag)
    }

    private fun HttpResponse.newETagCache(releases: List<ReleaseInfo>): ETagCache? =
        headers[HttpHeaders.ETag]?.let { ETagCache(it, releases) }
}

/** [releases] as they stood the last time this endpoint returned [etag] — replayed verbatim on a
 * subsequent 304, which by definition means GitHub would have sent this exact same content again. */
private data class ETagCache(val etag: String, val releases: List<ReleaseInfo>)
