package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NextcloudServerProblem
import works.merc.keryx.app.core.Result

/**
 * Checks that a normalized Nextcloud base URL (see [normalizeNextcloudUrl]) really is an installed
 * Nextcloud server, before the browser sign-in is started there: `GET {base}/status.php` must answer
 * 200 with a JSON object whose `installed` is `true`.
 *
 * Redirects are not followed (the shared client has `followRedirects = false`): a 3xx counts as "no
 * Nextcloud here", because the address the user confirms must be the one every later request goes
 * to — following it silently could also step down to plain HTTP.
 */
class NextcloudServerProbe(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Probes [baseUrl].
     *
     * @param baseUrl A base URL already normalized by [normalizeNextcloudUrl].
     * @return [Result.Ok] for an installed Nextcloud server; otherwise a [NextcloudServerException]:
     *   [NextcloudServerProblem.UNREACHABLE] when no response arrived at all,
     *   [NextcloudServerProblem.NOT_NEXTCLOUD] for any response that is not the expected status, and
     *   [NextcloudServerProblem.HTTPS_REQUIRED] for a base URL that is not HTTPS.
     */
    suspend fun probe(baseUrl: String): Result<Unit> {
        if (!isHttpsUrl(baseUrl)) {
            return Result.Err(NextcloudServerException(NextcloudServerProblem.HTTPS_REQUIRED, "Server address must use HTTPS"))
        }
        val response = try {
            client.get("$baseUrl/status.php") { header(HttpHeaders.UserAgent, APP_NAME) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.Err(
                NextcloudServerException(NextcloudServerProblem.UNREACHABLE, e.message ?: "Server did not respond"),
            )
        }
        return if (isInstalledNextcloud(response)) {
            Result.Ok(Unit)
        } else {
            Result.Err(
                NextcloudServerException(
                    NextcloudServerProblem.NOT_NEXTCLOUD,
                    "No installed Nextcloud server answered (HTTP ${response.status.value})",
                ),
            )
        }
    }

    private suspend fun isInstalledNextcloud(response: HttpResponse): Boolean {
        if (response.status.value != 200) return false
        val body = response.bodyAsText()
        val status = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
        return (status["installed"] as? JsonPrimitive)?.booleanOrNull == true
    }
}
