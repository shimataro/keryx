package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.merc.keryx.app.core.Log

private const val TAG = "UpdateChecker"

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus
    data class Available(val version: String, val url: String) : UpdateStatus
    data object Failed : UpdateStatus
}

/**
 * Polls a GitHub repo's `releases/latest` for a newer version than [currentVersion]. No
 * telemetry, no auth — a single unauthenticated GET against GitHub's public API.
 */
class UpdateChecker(
    private val client: HttpClient,
    private val currentVersion: String,
    private val repoSlug: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun check(): UpdateStatus {
        return try {
            val response = client.get("https://api.github.com/repos/$repoSlug/releases/latest") {
                // GitHub's API rejects requests with no User-Agent (403) — Ktor doesn't send one
                // by default, so this must be set explicitly.
                header(HttpHeaders.UserAgent, "Keryx/$currentVersion")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (response.status.value !in 200..299) {
                Log.warn(TAG, "Update check failed: HTTP ${response.status.value}")
                return UpdateStatus.Failed
            }
            val body = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: unexpected response body") }
            val tagName = body["tag_name"]?.jsonPrimitive?.content
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing tag_name") }
            val htmlUrl = body["html_url"]?.jsonPrimitive?.content
                ?: return UpdateStatus.Failed.also { Log.warn(TAG, "Update check failed: missing html_url") }

            val remoteVersion = tagName.removePrefix("v").removePrefix("V")
            if (isNewer(remoteVersion, currentVersion)) {
                UpdateStatus.Available(remoteVersion, htmlUrl)
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
 * `intervalHours <= 0` means "startup checks only" and is never due for a periodic recheck.
 * The startup check itself runs unconditionally and doesn't go through this function.
 */
internal fun shouldCheckForUpdate(nowMillis: Long, lastCheckMillis: Long?, intervalHours: Int): Boolean =
    intervalHours > 0 && (lastCheckMillis == null || nowMillis - lastCheckMillis >= intervalHours * 3_600_000L)
