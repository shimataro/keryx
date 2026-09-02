package works.merc.keryx.app.domain

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
import works.merc.keryx.app.core.MILLIS_PER_HOUR
import works.merc.keryx.app.core.compareReleaseVersions
import works.merc.keryx.app.core.isBelowStable
import works.merc.keryx.app.core.isNewer
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.detectInstallLocation

private const val TAG = "UpdateChecker"

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus

    /**
     * @param version The release version (`tag_name` with its leading `v`/`V` stripped).
     * @param url The release page (`html_url`) — the only actionable destination for a caller
     *   that hasn't been updated to know about [asset]/[UpdatePlan] yet.
     * @param releaseNotes The release's Markdown body (`body`), or `null` when GitHub didn't
     *   return one. Defaulted so every pre-existing call site (all of which predate this field)
     *   keeps compiling unchanged.
     * @param asset The release asset [selectUpdateAsset] picked for the current install form, or
     *   `null` when none applies (no matching asset in this release, or an unrecognized/unsupported
     *   install form). Defaulted for the same reason as [releaseNotes].
     */
    data class Available(
        val version: String,
        val url: String,
        val releaseNotes: String? = null,
        val asset: UpdateAsset? = null,
    ) : UpdateStatus

    data object Failed : UpdateStatus
}

/**
 * Polls a GitHub repo for a newer version than [currentVersion]. No telemetry, no auth — a single
 * unauthenticated GET against GitHub's public API.
 *
 * Candidate policy: official (non-pre-release) releases are always eligible; a pre-release is
 * eligible only while both [currentVersion] and the release are below 1.0.0 (i.e. during the
 * pre-stable phase). Drafts are never eligible.
 *
 * The endpoint is chosen by [isBelowStable]:
 * - **Pre-stable build (0.x)**: the `releases` list endpoint, so pre-releases are visible
 *   (`releases/latest` would 404 for a repo whose only releases are pre-releases). Filtering and
 *   candidate selection then happen client-side over the returned page.
 * - **Stable build (1.0.0+, including a 1.x pre-release)**: the `releases/latest` endpoint, which
 *   returns *the* newest full (non-draft, non-pre-release) release server-side — no pagination
 *   concern regardless of how many pre-releases precede it. A 404 means the repo has no full
 *   release yet, i.e. nothing to offer (up to date).
 */
class UpdateChecker(
    private val client: HttpClient,
    private val currentVersion: String,
    private val repoSlug: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    // Defaulted (rather than injected via Koin) so every pre-existing call site keeps compiling
    // unchanged — see the KDoc on [UpdateStatus.Available]'s new fields for the same reasoning.
    private val location: InstallLocation = detectInstallLocation(),
) {
    suspend fun check(): UpdateStatus {
        return try {
            // Pre-releases are only offered while this build itself is still pre-1.0.0. The same
            // flag also picks the endpoint (list vs. releases/latest).
            val currentIsPreStable = isBelowStable(currentVersion)
            val releases = (if (currentIsPreStable) fetchReleaseList() else fetchLatestRelease())
                ?: return UpdateStatus.Failed

            val candidate = releases
                .filter { !(it["draft"]?.jsonPrimitive?.booleanOrNull ?: false) }
                .filter { release ->
                    val isPreRelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                    !isPreRelease || (currentIsPreStable && isBelowStable(versionOf(release)))
                }
                // GitHub returns releases newest-first, but don't rely on that: pick the highest
                // version so a rejected newer pre-release never hides an older eligible stable one.
                .maxWithOrNull { a, b -> compareReleaseVersions(versionOf(a), versionOf(b)) }
                ?: return UpdateStatus.UpToDate // no eligible release → nothing to offer

            val remoteVersion = versionOf(candidate)
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing tag_name") }
            if (!isSafeVersionForPathUse(remoteVersion)) {
                // remoteVersion ends up as a path component (UpdateRepository's updateDownloadDir),
                // so a tag_name containing '/', a backslash, or anything else outside a plain version's
                // alphabet is rejected outright here rather than sanitized — see this function's
                // own KDoc.
                return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: tag_name is not a safe version string") }
            }
            val htmlUrl = candidate["html_url"]?.jsonPrimitive?.content
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing html_url") }

            if (isNewer(remoteVersion, currentVersion)) {
                val releaseNotes = candidate["body"]?.jsonPrimitive?.contentOrNull
                val asset = selectUpdateAsset(releaseAssetsOf(candidate), location)
                UpdateStatus.Available(remoteVersion, htmlUrl, releaseNotes, asset)
            } else {
                UpdateStatus.UpToDate
            }
        } catch (e: CancellationException) {
            throw e // don't swallow coroutine cancellation as a failed update check
        } catch (e: Exception) {
            Log.warn(TAG, "Update check failed", e)
            UpdateStatus.Failed
        }
    }

    /**
     * Fetches the full `releases` list (pre-stable path). Returns the release objects, or `null` on
     * HTTP error / unexpected body (→ [UpdateStatus.Failed]). `per_page=100` raises the single-page
     * limit from GitHub's default 30 so realistic release histories fit in one request.
     */
    private suspend fun fetchReleaseList(): List<JsonObject>? {
        val response = client.get("https://api.github.com/repos/$repoSlug/releases?per_page=100") {
            applyGitHubHeaders()
        }
        if (response.status.value !in 200..299) {
            Log.warn(TAG, "Update check failed: HTTP ${response.status.value}")
            return null
        }
        val array = json.parseToJsonElement(response.bodyAsText()) as? JsonArray
            ?: return null.also { Log.warn(TAG, "Update check failed: unexpected response body") }
        return array.mapNotNull { it as? JsonObject }
    }

    /**
     * Fetches the newest full release via `releases/latest` (stable path). Returns a single-element
     * list, an empty list on 404 (no full release yet → [UpdateStatus.UpToDate]), or `null` on any
     * other HTTP error / unexpected body (→ [UpdateStatus.Failed]).
     */
    private suspend fun fetchLatestRelease(): List<JsonObject>? {
        val response = client.get("https://api.github.com/repos/$repoSlug/releases/latest") {
            applyGitHubHeaders()
        }
        if (response.status.value == 404) return emptyList()
        if (response.status.value !in 200..299) {
            Log.warn(TAG, "Update check failed: HTTP ${response.status.value}")
            return null
        }
        val obj = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return null.also { Log.warn(TAG, "Update check failed: unexpected response body") }
        return listOf(obj)
    }

    /** Parses the release's `assets[]` array into [ReleaseAsset]s, skipping any entry missing a
     * required field rather than failing the whole check over one malformed asset. */
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

/**
 * Extracts a release version from its `tag_name`, removing a leading `v` or `V`.
 *
 * @return The version string, or `null` when `tag_name` is unavailable.
 */
private fun versionOf(release: JsonObject): String? =
    release["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")?.removePrefix("V")

/** Matches a `tag_name`-derived version string safe to use as a single path component — letters,
 * digits, `.`, `+`, `-` only. `core/SemVer.kt`'s own core-parsing only validates up to the first
 * `-`/`+`, leaving everything after it (a prerelease/build-metadata suffix) — where `/`, `\`, or
 * `..` could hide — unchecked; this is the gate that actually matters once the version is about to
 * become a directory name ([works.merc.keryx.app.domain.UpdateRepository]'s `updateDownloadDir`). */
private val SAFE_VERSION_PATTERN = Regex("^[A-Za-z0-9.+-]+$")

private fun isSafeVersionForPathUse(version: String): Boolean = SAFE_VERSION_PATTERN.matches(version)

/**
 * `intervalHours <= 0` means "startup checks only" and is never due for a periodic recheck.
 * The startup check itself runs unconditionally and doesn't go through this function.
 */
internal fun shouldCheckForUpdate(nowMillis: Long, lastCheckMillis: Long?, intervalHours: Int): Boolean =
    intervalHours > 0 && (lastCheckMillis == null || nowMillis - lastCheckMillis >= intervalHours * MILLIS_PER_HOUR)
