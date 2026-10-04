package works.merc.keryx.app.data.cloud

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.ktor.client.HttpClient
import works.merc.keryx.app.core.AndroidGoogleDriveBackend
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.domain.CloudSession

/**
 * Not an OAuth client id. Android's Google Drive never sends one — Play services identifies the app
 * by package name and signing certificate — but [CloudSession] reads `clientId` as "is this backend
 * configured in this build at all", so it has to be non-empty. Whether Google Drive is actually
 * offerable here is decided by [PlayServicesGoogleDriveBackend.isAvailable] (does this device have
 * Play services), which also gates whether this provider is registered at all.
 */
private const val PLAY_SERVICES_CLIENT_ID = "play-services"

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
     * [CloudSession.Provider.accessTokenProvider] is what makes this work inside `CloudSession`: Play
     * services owns the grant and mints a one-hour token on demand, so there is no refresh token and
     * the ordinary stored-token + refresh path would hand out an expired one. `allowUserInteraction`
     * is false here — a cloud request must never open a consent screen on its own, least of all from
     * the `WorkManager` background sync, which has no Activity to open it from.
     */
    override fun provider(client: HttpClient, tokenStorage: TokenStorage): CloudSession.Provider {
        val authorization = PlayServicesAuthorization()
        return CloudSession.Provider(
            clientId = PLAY_SERVICES_CLIENT_ID,
            tokenStorage = tokenStorage,
            authManager = PlayServicesGoogleDriveAuthManager(client, authorization),
            connectFlow = PlayServicesConnectFlow(authorization),
            createStorage = { tokenProvider -> GoogleDriveStorage(client, tokenProvider) },
            accessTokenProvider = { authorization.accessToken(allowUserInteraction = false).valueOrNull },
        )
    }
}
