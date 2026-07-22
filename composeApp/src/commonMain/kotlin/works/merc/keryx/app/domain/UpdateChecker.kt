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
 * SemVer comparison following prerelease precedence: `[remote] > [local]`? The numeric core
 * (`major.minor.patch`) is compared first, then a prerelease suffix (`-beta`, `-rc.1`, …) ranks
 * *below* the same core without one. An unparseable core (non-numeric segment) is treated as safely
 * "not newer" (returns false) rather than throwing — a malformed remote tag should never be
 * reported as an available update.
 */
internal fun isNewer(remote: String, local: String): Boolean =
    compareVersions(remote, local)?.let { it > 0 } ?: false

/**
 * Three-way SemVer comparison of two version strings (leading `v` already stripped by [versionOf]).
 * Returns a negative/zero/positive Int like [Comparator], or `null` when either core has a
 * non-numeric segment (undeterminable → callers treat as "not newer"). Build metadata (`+...`) is
 * stripped and ignored for precedence per SemVer §10.
 */
private fun compareVersions(a: String, b: String): Int? {
    // SemVer §10: build metadata is ignored for precedence. Strip it first — it may follow either
    // the core (`1.0.0+001`) or the prerelease (`1.0.0-alpha+001`).
    val aClean = a.substringBefore('+')
    val bClean = b.substringBefore('+')
    val aCore = aClean.substringBefore('-').split(".").map { it.toIntOrNull() }
    val bCore = bClean.substringBefore('-').split(".").map { it.toIntOrNull() }
    if (aCore.any { it == null } || bCore.any { it == null }) return null

    val length = maxOf(aCore.size, bCore.size)
    for (i in 0 until length) {
        val ai = aCore.getOrElse(i) { 0 } ?: 0
        val bi = bCore.getOrElse(i) { 0 } ?: 0
        if (ai != bi) return ai.compareTo(bi)
    }

    // Cores equal → compare prerelease per SemVer: absence of a prerelease outranks its presence.
    val aPre = aClean.substringAfter('-', "")
    val bPre = bClean.substringAfter('-', "")
    if (aPre.isEmpty() && bPre.isEmpty()) return 0
    if (aPre.isEmpty()) return 1
    if (bPre.isEmpty()) return -1
    return comparePrerelease(aPre, bPre)
}

/**
 * Compares two dot-separated prerelease strings per SemVer §11: identifiers are compared field by
 * field; numeric identifiers compare numerically and rank below alphanumeric ones, alphanumeric
 * identifiers compare lexically (ASCII), and when all shared fields are equal the longer list wins.
 */
private fun comparePrerelease(a: String, b: String): Int {
    val aIds = a.split(".")
    val bIds = b.split(".")
    for (i in 0 until minOf(aIds.size, bIds.size)) {
        val aId = aIds[i]
        val bId = bIds[i]
        val aNum = aId.toIntOrNull()
        val bNum = bId.toIntOrNull()
        val cmp = when {
            aNum != null && bNum != null -> aNum.compareTo(bNum)
            aNum != null -> -1 // numeric ranks below alphanumeric
            bNum != null -> 1
            else -> aId.compareTo(bId)
        }
        if (cmp != 0) return cmp
    }
    return aIds.size.compareTo(bIds.size)
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
