package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.MILLIS_PER_HOUR
import works.merc.keryx.app.core.compareReleaseVersions
import works.merc.keryx.app.core.isBelowStable
import works.merc.keryx.app.core.isNewer
import works.merc.keryx.app.data.remote.ReleaseFeedSource
import works.merc.keryx.app.data.remote.ReleaseInfo
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
    client: HttpClient,
    private val currentVersion: String,
    private val repoSlug: String,
    json: Json = Json { ignoreUnknownKeys = true },
    // Defaulted (rather than injected via Koin) so every pre-existing call site keeps compiling
    // unchanged — see the KDoc on [UpdateStatus.Available]'s new fields for the same reasoning.
    private val location: InstallLocation = detectInstallLocation(),
) {
    // The raw HTTP request/JSON parsing lives in data/remote/ReleaseFeedSource — this class keeps
    // only the policy above it (which candidate, whether it's newer, what to do with its asset).
    // Built internally rather than injected so this class's own constructor — used directly by a
    // large number of tests — doesn't have to change shape for what's an implementation detail.
    private val releaseFeedSource = ReleaseFeedSource(client, currentVersion, json)

    suspend fun check(): UpdateStatus {
        return try {
            // Pre-releases are only offered while this build itself is still pre-1.0.0. The same
            // flag also picks the endpoint (list vs. releases/latest).
            val currentIsPreStable = isBelowStable(currentVersion)
            val releases = (
                if (currentIsPreStable) releaseFeedSource.fetchReleaseList(repoSlug) else releaseFeedSource.fetchLatestRelease(repoSlug)
                ) ?: return UpdateStatus.Failed

            val candidate = releases
                .filter { !it.draft }
                .filter { release -> !release.prerelease || (currentIsPreStable && isBelowStable(versionOf(release))) }
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
            val htmlUrl = candidate.htmlUrl
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing html_url") }

            if (isNewer(remoteVersion, currentVersion)) {
                val asset = selectUpdateAsset(candidate.assets, location)
                UpdateStatus.Available(remoteVersion, htmlUrl, candidate.body, asset)
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
}

/**
 * Extracts a release version from its `tag_name`, removing a leading `v` or `V`.
 *
 * @return The version string, or `null` when `tag_name` is unavailable.
 */
private fun versionOf(release: ReleaseInfo): String? =
    release.tagName?.removePrefix("v")?.removePrefix("V")

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
