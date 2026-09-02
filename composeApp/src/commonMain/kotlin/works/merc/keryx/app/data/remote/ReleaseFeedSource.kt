package works.merc.keryx.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
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
    /**
     * Fetches the full `releases` list (pre-stable path — see [works.merc.keryx.app.domain.UpdateChecker]'s own KDoc for why).
     * `per_page=100` raises the single-page limit from GitHub's default 30 so realistic release
     * histories fit in one request. Converts every failure mode — HTTP error, unexpected body, a
     * network exception — into [UpdateException]`(`[UpdateStage.CHECK]`, …)` here rather than
     * leaking it upward, per this codebase's error-design convention for the DataSource layer.
     */
    suspend fun fetchReleaseList(repoSlug: String): Result<List<ReleaseInfo>> = fetchReleases {
        val response = client.get("https://api.github.com/repos/$repoSlug/releases?per_page=100") {
            applyGitHubHeaders()
        }
        if (response.status.value !in 200..299) {
            return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "HTTP ${response.status.value}"))
        }
        val array = json.parseToJsonElement(response.bodyAsText()) as? JsonArray
            ?: return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "Unexpected response body"))
        Result.Ok(array.mapNotNull { (it as? JsonObject)?.let(::releaseInfoOf) })
    }

    /**
     * Fetches the newest full release via `releases/latest` (stable path — see [works.merc.keryx.app.domain.UpdateChecker]'s
     * own KDoc for why). Resolves to a single-element list, or an empty one on 404 (no full release
     * yet — the caller treats that as up to date, not a failure). Every other failure mode is
     * converted the same way [fetchReleaseList] converts its own.
     */
    suspend fun fetchLatestRelease(repoSlug: String): Result<List<ReleaseInfo>> = fetchReleases {
        val response = client.get("https://api.github.com/repos/$repoSlug/releases/latest") {
            applyGitHubHeaders()
        }
        if (response.status.value == 404) return@fetchReleases Result.Ok(emptyList())
        if (response.status.value !in 200..299) {
            return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "HTTP ${response.status.value}"))
        }
        val obj = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@fetchReleases Result.Err(UpdateException(UpdateStage.CHECK, "Unexpected response body"))
        Result.Ok(listOf(releaseInfoOf(obj)))
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
}
