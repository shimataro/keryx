package works.merc.keryx.app.data.cloud

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.GOOGLE_DRIVE_APPDATA_SCOPE
import works.merc.keryx.app.core.GOOGLE_REVOKE_ENDPOINT
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.map
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.platform.AndroidAppContext
import works.merc.keryx.app.platform.AndroidAuthorizationHost
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PlayServicesGDriveAuth"

/**
 * Obtains Google Drive access tokens on Android through Play services'
 * [com.google.android.gms.auth.api.identity.AuthorizationClient].
 *
 * **Why not the browser PKCE flow every other provider uses.** Google's OAuth policy deprecates
 * both the custom-URI-scheme redirect and the loopback redirect for its Android client type, so
 * there is no redirect-based flow left to run here — `AuthorizationClient` is the sanctioned
 * replacement. It hands out a short-lived (one hour) access token directly on the device and
 * keeps the grant itself, which means **no refresh token, no `client_secret`, and no backend**;
 * see `docs/sync-architecture.md`'s "Google Drive on Android".
 *
 * The Android OAuth client is never named in code: Play services identifies the app by its package
 * name and signing certificate, so an unregistered signing key surfaces as an authorization
 * failure here rather than as a missing configuration value at build time.
 *
 * A result is only accepted once it actually carries the requested scope — Play services can
 * answer a partially-granted consent with a token that is useless for Drive. See [tokenFrom].
 */
class PlayServicesAuthorization(
    private val context: Context = AndroidAppContext.application,
) {
    private val client get() = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest
        get() = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(GOOGLE_DRIVE_APPDATA_SCOPE)))
            .build()

    /**
     * Answers with a currently-valid access token.
     *
     * @param allowUserInteraction whether the consent screen may be shown. `true` only for the
     * interactive connect; every later call (each cloud request, and the revoke on disconnect)
     * passes `false`, so a grant the user has since withdrawn fails as an ordinary authentication
     * error instead of popping a consent screen out of a background sync — or out of a *disconnect*,
     * where asking for consent to then throw it away would be absurd.
     */
    suspend fun accessToken(allowUserInteraction: Boolean): Result<String> {
        val result = try {
            client.authorize(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.warn(TAG, "Play services authorization failed", e)
            return Result.Err(CloudAuthException(e.message ?: "Google Drive authorization failed"))
        }
        if (!result.hasResolution()) return tokenOf(result)

        if (!allowUserInteraction) {
            // Not an error worth logging at WARN: this is the ordinary "the user has to re-consent"
            // state, which the caller turns into a normal CloudAuthException for the bell.
            return Result.Err(CloudAuthException("Google Drive authorization needs the user's consent"))
        }
        val pendingIntent = result.pendingIntent
            ?: return Result.Err(CloudAuthException("Google Drive authorization returned no consent screen"))

        val data = AndroidAuthorizationHost.launch(pendingIntent)
            ?: return Result.Err(CloudAuthException("Google Drive authorization was dismissed"))
        return try {
            tokenOf(client.getAuthorizationResultFromIntent(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.warn(TAG, "Play services consent screen returned an unusable result", e)
            return Result.Err(CloudAuthException(e.message ?: "Google Drive authorization failed"))
        }
    }

    /**
     * Drops [token] from Play services' own token cache.
     *
     * **This is what keeps a disconnect from breaking the next connect.** Revoking the grant at
     * Google (see [PlayServicesGoogleDriveAuthManager.revoke]) happens over plain HTTPS, outside
     * Play services, so Play services never learns that the authorization it is holding has died.
     * Without this call it keeps answering [accessToken] from that cache — successfully, with
     * `hasResolution()` clear — so the next connect completes with no consent screen and every
     * Drive request afterwards fails with 401, out of the app's reach to fix. Confirmed on-device:
     * clearing the token here is what brings the consent screen back.
     *
     * `AuthorizationClient.revokeAccess` looks like it should replace the HTTPS revoke and make
     * this unnecessary, but it requires an `Account` that nothing else in this flow tracks — called
     * without one it fails inside Play services with
     * `NullPointerException: ... Account.name on a null object reference`.
     */
    suspend fun clearToken(token: String): Result<Unit> = bestEffort("clear the cached Google Drive token") {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitCompletion()
    }

    /**
     * Runs a Play services call whose failure must not abort the caller — it is a step of a
     * disconnect, which has to finish clearing local state either way. Logs and reports the failure
     * rather than throwing.
     */
    private suspend fun bestEffort(what: String, block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.Ok(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.warn(TAG, "Play services failed to $what", e)
        Result.Err(CloudAuthException(e.message ?: "Failed to $what"))
    }

    private fun tokenOf(result: AuthorizationResult): Result<String> =
        tokenFrom(result.accessToken, result.grantedScopes)
}

/**
 * The accept/reject decision for an [AuthorizationResult], kept as a top-level function rather than
 * a member so it is testable without building a Play services result object (whose only constructor
 * is the `SafeParcelable` one).
 *
 * The scope check is not belt-and-braces. Google's granular-permissions guidance is explicit that an
 * app must check which scopes the user actually granted instead of assuming the requested set was
 * approved wholesale — the consent screen lets a scope be declined on its own, and the result still
 * carries an access token when that happens. Accepting such a token would let the connect report
 * success and persist `cloudStorageType`, leaving the failure to surface much later as a 403 on
 * every Drive request.
 */
internal fun tokenFrom(accessToken: String?, grantedScopes: List<String>): Result<String> {
    if (GOOGLE_DRIVE_APPDATA_SCOPE !in grantedScopes) {
        return Result.Err(CloudAuthException("Google Drive app-data access was not granted"))
    }
    return accessToken?.let { Result.Ok(it) }
        ?: Result.Err(CloudAuthException("Google Drive authorization returned no access token"))
}

/**
 * Android's Google Drive connect flow. Unlike [OAuthConnectFlow][works.merc.keryx.app.domain.OAuthConnectFlow]
 * there is no PKCE, no browser and no redirect to capture — Play services runs the whole consent
 * interaction and answers with the token.
 *
 * The returned [OAuthTokens] carries no refresh token and no expiry: Play services owns the grant
 * and re-issues a token on demand, so there is nothing to refresh and no deadline worth recording.
 * `CloudSession.Provider.accessTokenProvider` is what keeps `CloudSession` from trying to refresh
 * it; the stored copy exists only so a restart still reports the account as connected.
 */
class PlayServicesConnectFlow(
    private val authorization: PlayServicesAuthorization,
) : CloudConnectFlow {
    /**
     * Drops whatever token Play services still has cached *before* asking for one.
     *
     * Connecting is an explicit user action, so it must be authoritative rather than trusting Play
     * services' cached view. When the grant was withdrawn outside this app — the user removing
     * Keryx at myaccount.google.com/connections, say — Play services keeps answering with the
     * token it cached, successfully and with `hasResolution()` clear. Without this, pressing
     * "connect" (the obvious thing to do when sync reports an authentication failure) would appear
     * to succeed while every Drive request kept failing with 401, and nothing the user could do
     * from inside the app would fix it.
     *
     * Costs one extra Play services round trip per explicit connect, and nothing else: if the grant
     * is still good, Play services simply mints a new token and no consent screen appears. This
     * also covers a stale cache arriving by any other route, not just an external revoke.
     */
    override suspend fun connect(): Result<OAuthTokens> {
        authorization.accessToken(allowUserInteraction = false).valueOrNull
            ?.let { authorization.clearToken(it) }
        return authorization.accessToken(allowUserInteraction = true)
            .map { OAuthTokens(accessToken = it, refreshToken = null, expiresAtMillis = null) }
    }
}

/**
 * The [CloudAuthManager] slot for Android's Google Drive. Only [revoke] does anything: this
 * provider supplies both its own [CloudConnectFlow] and its own
 * `CloudSession.Provider.accessTokenProvider`, so the browser-PKCE members below are never reached.
 */
class PlayServicesGoogleDriveAuthManager(
    private val httpClient: HttpClient,
    private val authorization: PlayServicesAuthorization,
) : CloudAuthManager {

    override fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String = error(UNREACHABLE)

    override suspend fun exchangeCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<OAuthTokens> = Result.Err(CloudAuthException(UNREACHABLE))

    override suspend fun refresh(clientId: String, refreshToken: String): Result<OAuthTokens> =
        Result.Err(CloudAuthException(UNREACHABLE))

    /**
     * Revokes the grant, best-effort as [CloudAuthManager.revoke] specifies.
     *
     * [accessToken] — the copy `CloudSession` kept from the connect — is deliberately ignored: it
     * expires after an hour, so by disconnect time it is usually dead and Google's revoke endpoint
     * would simply reject it. A fresh one is fetched instead, without user interaction, and when
     * even that is unavailable (the user already withdrew the grant in their Google account) this
     * reports success rather than opening a consent screen mid-disconnect. `CloudSession.disconnect`
     * clears the local tokens regardless of what this returns.
     *
     * [PlayServicesAuthorization.clearToken] afterwards is not optional bookkeeping — without it
     * Play services keeps serving this now-dead token and the next connect silently succeeds with
     * it. See that method's KDoc for the failure it prevents.
     */
    override suspend fun revoke(accessToken: String): Result<Unit> {
        val fresh = authorization.accessToken(allowUserInteraction = false).valueOrNull
            ?: return Result.Ok(Unit)
        val revoked = revokeOAuthToken {
            httpClient.submitForm(GOOGLE_REVOKE_ENDPOINT, parameters { append("token", fresh) })
        }
        authorization.clearToken(fresh)
        return revoked
    }

    private companion object {
        const val UNREACHABLE =
            "Google Drive on Android does not use the browser PKCE flow (see PlayServicesGoogleDriveAuthManager)"
    }
}

/** Bridges a Play services [Task] to a coroutine without depending on kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/**
 * [await] for a `Task<Void>`. Its success value is always null, which [await]'s non-null `T` cannot
 * carry, so completion is reported as [Unit] instead.
 */
private suspend fun Task<Void>.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(Unit) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
