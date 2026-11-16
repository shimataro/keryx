package works.merc.keryx.app.data.cloud.nextcloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.NEXTCLOUD_LOGIN_POLL_INTERVAL_MS
import works.merc.keryx.app.core.NEXTCLOUD_LOGIN_TIMEOUT_MS
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.AppPasswordCredentials
import works.merc.keryx.app.data.cloud.CloudCredentials
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudConnectRequest
import works.merc.keryx.app.domain.LoginPageLauncher
import works.merc.keryx.app.platform.platformOsName

/**
 * The name this app introduces itself with to a Nextcloud server — e.g. `Keryx (Android)`. Login
 * Flow v2 shows it as the new app password's name in the account's "Devices & sessions", so the
 * user can tell which device a password belongs to when revoking it there.
 */
fun nextcloudClientName(): String = "$APP_NAME ($platformOsName)"

/**
 * Connects a Nextcloud account through Login Flow v2, returning the app password it issues as
 * [AppPasswordCredentials]. Not OAuth: there is no redirect back to the app. Instead:
 *
 * 1. `POST {base}/index.php/login/v2` returns a sign-in page URL and a poll token/endpoint.
 * 2. The sign-in page is opened through [loginPageLauncher]; the user signs in there (2FA and SSO
 *    included — the server handles all of it) and grants access.
 * 3. The poll endpoint is asked every [pollIntervalMs] whether that has happened: 404 until it has,
 *    then 200 once, carrying `server`, `loginName` and `appPassword`. The server forgets the request
 *    after 20 minutes ([timeoutMs]).
 * 4. `loginName` need not be the account's user id, which the WebDAV paths need, so the id is read
 *    from OCS `GET /ocs/v1.php/cloud/user`.
 *
 * Every URL involved — the base, the sign-in page, the poll endpoint, and the `server` the grant
 * names — must be HTTPS, or the flow fails before the app password could travel over it. Whether a
 * Nextcloud server answers at the base at all was already checked by the connect dialog
 * (`NextcloudServerController`), so it is not probed again here.
 *
 * [LoginPageLauncher.close] runs once the sign-in page has been opened, however the flow ends —
 * success, failure, or cancellation.
 */
class NextcloudLoginFlow(
    private val client: HttpClient,
    private val loginPageLauncher: LoginPageLauncher,
    private val userAgent: String = nextcloudClientName(),
    private val pollIntervalMs: Long = NEXTCLOUD_LOGIN_POLL_INTERVAL_MS,
    private val timeoutMs: Long = NEXTCLOUD_LOGIN_TIMEOUT_MS,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudConnectFlow {

    override suspend fun connect(request: CloudConnectRequest): Result<CloudCredentials> {
        val requested = request.serverUrl ?: return authError("No Nextcloud server address given")
        val base = when (val normalized = normalizeNextcloudUrl(requested)) {
            is Result.Ok -> normalized.value
            is Result.Err -> return normalized
        }
        var pageOpened = false
        return try {
            val login = startLogin(base) ?: return authError("Nextcloud did not start a sign-in")
            loginPageLauncher.open(login.pageUrl)
            pageOpened = true
            val grant = withTimeoutOrNull(timeoutMs) { awaitGrant(login) }
                ?: return authError("Nextcloud sign-in timed out")
            finishLogin(grant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            authError(e.message ?: "Nextcloud sign-in failed")
        } finally {
            if (pageOpened) loginPageLauncher.close()
        }
    }

    /** Step 1: asks [base] for a sign-in page and poll endpoint; `null` when the answer is unusable. */
    private suspend fun startLogin(base: String): PendingLogin? {
        val response = client.post("$base/index.php/login/v2") { header(HttpHeaders.UserAgent, userAgent) }
        if (response.status.value != 200) return null
        val body = parseObject(response.bodyAsText()) ?: return null
        val poll = body["poll"] as? JsonObject ?: return null
        val pending = PendingLogin(
            pageUrl = body.string("login") ?: return null,
            pollEndpoint = poll.string("endpoint") ?: return null,
            pollToken = poll.string("token") ?: return null,
        )
        // Never open a sign-in page, or send the poll token, anywhere but HTTPS.
        return pending.takeIf { isHttpsUrl(it.pageUrl) && isHttpsUrl(it.pollEndpoint) }
    }

    /**
     * Step 3: polls until the user has granted access. A 404 means not yet; any other answer, or a
     * request that fails outright (a network blip), is retried too — only [timeoutMs] ends the wait,
     * so a brief loss of connectivity while the user is signing in does not throw their sign-in away.
     */
    private suspend fun awaitGrant(login: PendingLogin): Grant {
        while (true) {
            pollOnce(login)?.let { return it }
            delay(pollIntervalMs)
        }
    }

    private suspend fun pollOnce(login: PendingLogin): Grant? {
        val response = try {
            client.submitForm(url = login.pollEndpoint, formParameters = parameters { append("token", login.pollToken) }) {
                header(HttpHeaders.UserAgent, userAgent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        if (response.status.value != 200) return null
        val body = parseObject(response.bodyAsText()) ?: return null
        return Grant(
            server = body.string("server") ?: return null,
            loginName = body.string("loginName") ?: return null,
            appPassword = body.string("appPassword") ?: return null,
        )
    }

    /**
     * Step 4: checks the granted server and reads the account's user id. An app password the flow
     * cannot finish with is revoked again (best effort), so it does not linger in the account's
     * "Devices & sessions" with no device holding it.
     */
    private suspend fun finishLogin(grant: Grant): Result<CloudCredentials> {
        val server = when (val normalized = normalizeNextcloudUrl(grant.server)) {
            is Result.Ok -> normalized.value
            // A server that hands out an http:// (or otherwise unusable) address: nothing it issued
            // can be used safely, and revoking would send the password there — so just drop it.
            is Result.Err -> return authError("Nextcloud granted access for an unusable server address")
        }
        val provisional = AppPasswordCredentials(
            serverUrl = server,
            userId = grant.loginName,
            loginName = grant.loginName,
            appPassword = grant.appPassword,
        )
        val userId = fetchUserId(provisional) ?: run {
            revokeNextcloudAppPassword(client, provisional, userAgent)
            return authError("Could not read the Nextcloud user id")
        }
        return Result.Ok(provisional.copy(userId = userId))
    }

    /** The account's user id from OCS `cloud/user` (`ocs.data.id`), or `null` when it cannot be read. */
    private suspend fun fetchUserId(credentials: AppPasswordCredentials): String? {
        val response = client.get("${credentials.serverUrl}/ocs/v1.php/cloud/user?format=json") {
            ocsAuth(credentials.loginName, credentials.appPassword, userAgent)
        }
        if (response.status.value != 200) return null
        val ocs = parseObject(response.bodyAsText())?.get("ocs") as? JsonObject ?: return null
        val data = ocs["data"] as? JsonObject ?: return null
        return data.string("id")?.takeIf { it.isNotBlank() }
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun authError(message: String): Result.Err = Result.Err(CloudAuthException(message))

    private class PendingLogin(val pageUrl: String, val pollEndpoint: String, val pollToken: String)

    private class Grant(val server: String, val loginName: String, val appPassword: String) {
        /** Masks [appPassword], like [AppPasswordCredentials.toString]. */
        override fun toString(): String = "Grant(server=$server, loginName=$loginName, appPassword=***)"
    }
}

