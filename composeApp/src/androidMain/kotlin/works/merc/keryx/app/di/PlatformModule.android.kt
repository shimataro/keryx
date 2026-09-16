package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.valueOrNull
import works.merc.keryx.app.data.cloud.FileTokenStorage
import works.merc.keryx.app.data.cloud.GoogleDriveStorage
import works.merc.keryx.app.data.cloud.KeystoreTokenStorage
import works.merc.keryx.app.data.cloud.PlayServicesAuthorization
import works.merc.keryx.app.data.cloud.PlayServicesConnectFlow
import works.merc.keryx.app.data.cloud.PlayServicesGoogleDriveAuthManager
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.platform.AndroidAppContext
import works.merc.keryx.app.platform.AndroidNotificationSink
import works.merc.keryx.app.platform.update.AndroidUpdateInstaller

/**
 * One secure-store instance per provider (never shared — [KeystoreTokenStorage] holds a
 * provider-scoped Keystore key alias and file). The account name is derived from
 * [CloudStorageType.id], matching desktop's own per-provider naming so a Dropbox-linked device's
 * `id` scheme lines up with the desktop actual (`providerTokenStorage` in
 * `PlatformModule.desktop.kt`) even though the encryption mechanism differs.
 */
private fun providerTokenStorage(type: CloudStorageType): TokenStorage =
    KeystoreTokenStorage(fallback = FileTokenStorage(fileName = ".${type.id}_tokens.json"), account = type.id)

/**
 * Not an OAuth client id. Android's Google Drive never sends one — Play services identifies the app
 * by package name and signing certificate — but [CloudSession] reads `clientId` as "is this backend
 * configured in this build at all", so it has to be non-empty. Whether Google Drive is actually
 * offerable here is decided by [CloudStorageAvailability.googleDriveAvailable] (does this device
 * have Play services), which also gates whether this provider is registered at all.
 */
private const val PLAY_SERVICES_CLIENT_ID = "play-services"

/**
 * Google Drive on Android: Play services' `AuthorizationClient`, not the browser PKCE flow the
 * other two providers (and desktop's own Google Drive) use — Google deprecated both redirect styles
 * for its Android client type. See `data/cloud/PlayServicesGoogleDriveAuth.kt` and
 * `docs/sync-architecture.md`'s "Google Drive on Android".
 *
 * [CloudSession.Provider.accessTokenProvider] is what makes this work inside `CloudSession`: Play
 * services owns the grant and mints a one-hour token on demand, so there is no refresh token and
 * the ordinary stored-token + refresh path would hand out an expired one. `allowUserInteraction`
 * is false here — a cloud request must never open a consent screen on its own, least of all from
 * the `WorkManager` background sync, which has no Activity to open it from.
 */
private fun googleDriveProvider(client: HttpClient): CloudSession.Provider {
    val authorization = PlayServicesAuthorization()
    return CloudSession.Provider(
        clientId = PLAY_SERVICES_CLIENT_ID,
        tokenStorage = providerTokenStorage(CloudStorageType.GOOGLE_DRIVE),
        authManager = PlayServicesGoogleDriveAuthManager(client, authorization),
        connectFlow = PlayServicesConnectFlow(authorization),
        createStorage = { tokenProvider -> GoogleDriveStorage(client, tokenProvider) },
        accessTokenProvider = { authorization.accessToken(allowUserInteraction = false).valueOrNull },
    )
}

/**
 * Android `platformModule`. Dropbox and OneDrive are wired the same way as desktop (PKCE public
 * client, `CustomUriRedirectTransport` over the shared `keryx://oauth2/callback` scheme — see
 * `.claude/rules/cloud-oauth-transport.md` and `di/CloudPlatformModule.kt`'s `cloudSessionSingles`);
 * the OS delivers the redirect to `MainActivity` (`AndroidManifest.xml`'s
 * `keryx://oauth2/callback` intent-filter), which forwards it into the shared
 * `MutableSharedFlow<OAuthCallbackParams>` via `dispatchOAuthCallbackIfPresent`.
 *
 * Google Drive takes a different route on this platform — Play services' `AuthorizationClient`
 * rather than a browser redirect — and is registered only on a device that actually has Play
 * services (see [googleDriveProvider] and `core/CloudStorageAvailability.android.kt`).
 * `DatabaseMerger`/`DatabaseSnapshot` are fully implemented (Android actuals), unlike the earlier
 * phases' stubs.
 */
actual val platformModule: Module = module {
    single<OsNotificationSink> { AndroidNotificationSink(AndroidAppContext.application) }

    single<UpdateInstaller> { AndroidUpdateInstaller() }

    single { keryxHttpClient(OkHttp) }

    cloudSessionSingles(
        tokenStorage = ::providerTokenStorage,
        // Registered only where Play services can actually serve it. A device without it (a
        // de-Googled ROM) never sees Google Drive offered, which is the same answer
        // CloudStorageAvailability gives the UI — the two read the same flag so they cannot drift.
        extraProviders = { client ->
            if (CloudStorageAvailability.googleDriveAvailable) {
                mapOf(CloudStorageType.GOOGLE_DRIVE to googleDriveProvider(client))
            } else {
                emptyMap()
            }
        },
    )
}
