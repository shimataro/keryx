package works.merc.keryx.app.data.cloud

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.ktor.client.HttpClient
import works.merc.keryx.app.core.AndroidGoogleDriveBackend
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.domain.CloudSession

/**
 * Google Drive on Android: Play services' `AuthorizationClient`, not the browser PKCE flow the
 * other two providers (and desktop's own Google Drive) use — Google deprecated both redirect styles
 * for its Android client type. See [PlayServicesAuthorization] and `docs/sync-architecture.md`'s
 * "Google Drive on Android".
 *
 * Deliberately **not** keyed on the distribution flavor (`github` / `play`). A play-flavored APK
 * can be sideloaded outside Play and a github-flavored one runs perfectly well on a device that has
 * Play services, so the flavor answers the wrong question — whether Play services is usable *on
 * this device* is [isAvailable]'s job. (The `fdroid` flavor has no Play services to ask: it does not
 * include this module at all.)
 */
object PlayServicesGoogleDriveBackend : AndroidGoogleDriveBackend {
    /** Only [ConnectionResult.SUCCESS] counts — see [AndroidGoogleDriveBackend.isAvailable]. */
    override fun isAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /**
     * [CloudSession.CredentialLifecycle.PlatformOwned] is what makes this work inside `CloudSession`: Play
     * services owns the grant and mints a one-hour token on demand, so there is no refresh token and
     * the ordinary stored-token + refresh path would hand out an expired one. `allowUserInteraction`
     * is false here — a cloud request must never open a consent screen on its own, least of all from
     * the `WorkManager` background sync, which has no Activity to open it from.
     */
    override fun provider(client: HttpClient, tokenStorage: TokenStorage): CloudSession.Provider {
        val authorization = PlayServicesAuthorization()
        return CloudSession.Provider(
            // Nothing to configure at build time: Play services identifies the app by package name
            // and signing certificate, and isAvailable already gates whether this provider exists.
            isConfigured = true,
            tokenStorage = tokenStorage,
            connectFlow = PlayServicesConnectFlow(authorization),
            lifecycle = CloudSession.CredentialLifecycle.PlatformOwned(
                accessTokenProvider = { authorization.accessToken(allowUserInteraction = false).valueOrNull },
                authManager = PlayServicesGoogleDriveAuthManager(client, authorization),
            ),
            createStorage = { credentials -> GoogleDriveStorage(client, oauthAccessToken(credentials)) },
        )
    }
}
