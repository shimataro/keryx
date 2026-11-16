package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.CloudCredentials

/** Adds the HTTP Basic credentials and the header Nextcloud's OCS API requires on every call. */
internal fun HttpRequestBuilder.ocsAuth(loginName: String, appPassword: String, userAgent: String) {
    basicAuth(loginName, appPassword)
    header("OCS-APIREQUEST", "true")
    header(HttpHeaders.Accept, "application/json")
    header(HttpHeaders.UserAgent, userAgent)
}

/**
 * Revokes the app password in [credentials] on its server (`DELETE /ocs/v2.php/core/apppassword`),
 * removing it from the account's "Devices & sessions". Used on disconnect and to discard an app
 * password the connect flow could not finish with.
 *
 * Best effort for every caller: the local copy is removed either way.
 *
 * @return [Result.Ok] once the server confirmed the revoke; otherwise an error describing why not.
 */
suspend fun revokeNextcloudAppPassword(
    client: HttpClient,
    credentials: CloudCredentials,
    userAgent: String,
): Result<Unit> {
    val appPassword = credentials as? AppPasswordCredentials
        ?: return Result.Err(CloudAuthException("Not Nextcloud credentials"))
    if (!isHttpsUrl(appPassword.serverUrl)) return Result.Err(CloudAuthException("Nextcloud server is not HTTPS"))
    return try {
        val response = client.delete("${appPassword.serverUrl}/ocs/v2.php/core/apppassword") {
            ocsAuth(appPassword.loginName, appPassword.appPassword, userAgent)
        }
        if (response.status.isSuccess()) {
            Result.Ok(Unit)
        } else {
            Result.Err(CloudStorageException("Nextcloud app password revoke failed (HTTP ${response.status.value})"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.Err(CloudStorageException(e.message ?: "Nextcloud app password revoke failed"))
    }
}
