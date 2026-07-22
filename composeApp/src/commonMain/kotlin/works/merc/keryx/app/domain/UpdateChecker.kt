package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import works.merc.keryx.app.core.Log

private const val TAG = "UpdateChecker"

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus
    data class Available(val version: String, val url: String) : UpdateStatus
    data object Failed : UpdateStatus
}

/**
 * Polls a GitHub repo's `releases` list for a newer version than [currentVersion]. No telemetry,
 * no auth — a single unauthenticated GET against GitHub's public API.
 *
 * The list endpoint (not `releases/latest`) is used so pre-releases are visible: `releases/latest`
 * only ever returns the newest non-draft, non-pre-release, which would be a 404 for a repo whose
 * only releases are pre-releases. Candidate policy: official (non-pre-release) releases are always
 * eligible; a pre-release is eligible only while both [currentVersion] and the release are below
 * 1.0.0 (i.e. during the pre-stable phase). Drafts are never eligible.
 */
class UpdateChecker(
    private val client: HttpClient,
    private val currentVersion: String,
    private val repoSlug: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun check(): UpdateStatus {
        return try {
            val response = client.get("https://api.github.com/repos/$repoSlug/releases") {
                // GitHub's API rejects requests with no User-Agent (403) — Ktor doesn't send one
                // by default, so this must be set explicitly.
                header(HttpHeaders.UserAgent, "Keryx/$currentVersion")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (response.status.value !in 200..299) {
                Log.warn(TAG, "Update check failed: HTTP ${response.status.value}")
                return UpdateStatus.Failed
            }
            val releases = json.parseToJsonElement(response.bodyAsText()) as? JsonArray
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: unexpected response body") }

            // Pre-releases are only offered while this build itself is still pre-1.0.0.
            val currentIsPreStable = isBelowStable(currentVersion)
            val candidate = releases
                .mapNotNull { it as? JsonObject }
                .filter { !(it["draft"]?.jsonPrimitive?.booleanOrNull ?: false) }
                .filter { release ->
                    val isPreRelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                    !isPreRelease || (currentIsPreStable && isBelowStable(versionOf(release)))
                }
                // GitHub returns releases newest-first, but don't rely on that: pick the highest
                // version so a rejected newer pre-release never hides an older eligible stable one.
                .maxWithOrNull { a, b -> if (isNewer(versionOf(b) ?: "", versionOf(a) ?: "")) -1 else 1 }
                ?: return UpdateStatus.UpToDate // no eligible release → nothing to offer

            val remoteVersion = versionOf(candidate)
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing tag_name") }
            val htmlUrl = candidate["html_url"]?.jsonPrimitive?.content
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing html_url") }

            if (isNewer(remoteVersion, currentVersion)) {
                UpdateStatus.Available(remoteVersion, htmlUrl)
            } else {
                UpdateStatus.UpToDate
            }
        } catch (e: Exception) {
            Log.warn(TAG, "Update check failed", e)
            UpdateStatus.Failed
        }
    }
}

/** Normalizes a release's `tag_name` (stripping a leading `v`/`V`) into a comparable version string. */
private fun versionOf(release: JsonObject): String? =
    release["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")?.removePrefix("V")

/**
 * Strict dot-separated numeric comparison. Unparseable segments are treated as safely "not
 * newer" (returns false) rather than throwing — a malformed remote tag should never be reported
 * as an available update.
 */
internal fun isNewer(remote: String, local: String): Boolean {
    val remoteParts = remote.split(".").map { it.toIntOrNull() }
    val localParts = local.split(".").map { it.toIntOrNull() }
    if (remoteParts.any { it == null } || localParts.any { it == null }) return false

    val length = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until length) {
        val r = remoteParts.getOrElse(i) { 0 } ?: 0
        val l = localParts.getOrElse(i) { 0 } ?: 0
        if (r != l) return r > l
    }
    return false
}

/**
 * True when [version]'s major component is 0 (i.e. below 1.0.0). Unparseable or null versions
 * return false so an undeterminable version is never treated as pre-stable (safe: excluded from
 * pre-release eligibility rather than wrongly included).
 */
internal fun isBelowStable(version: String?): Boolean =
    (version?.substringBefore('.')?.toIntOrNull() ?: return false) < 1

/**
 * `intervalHours <= 0` means "startup checks only" and is never due for a periodic recheck.
 * The startup check itself runs unconditionally and doesn't go through this function.
 */
internal fun shouldCheckForUpdate(nowMillis: Long, lastCheckMillis: Long?, intervalHours: Int): Boolean =
    intervalHours > 0 && (lastCheckMillis == null || nowMillis - lastCheckMillis >= intervalHours * 3_600_000L)
