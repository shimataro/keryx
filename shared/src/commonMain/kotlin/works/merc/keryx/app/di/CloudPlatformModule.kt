package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.module.Module
import works.merc.keryx.app.BuildConfig
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.DropboxStorage
import works.merc.keryx.app.data.cloud.OneDriveAuthManager
import works.merc.keryx.app.data.cloud.OneDriveStorage
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.AuthorizationLauncher
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.CustomUriRedirectTransport
import works.merc.keryx.app.domain.DefaultAuthorizationLauncher
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OAuthConnectFlow
import works.merc.keryx.app.domain.SettingsRepository

/**
 * Dropbox: custom URI scheme (`keryx://`) delivered by the OS — shared by desktop and Android,
 * whose `platformModule`s both wire this the same way (see `.claude/rules/cloud-oauth-transport.md`).
 */
internal fun dropboxProvider(
    client: HttpClient,
    callbackFlow: MutableSharedFlow<OAuthCallbackParams>,
    tokenStorage: TokenStorage,
    authorizationLauncher: AuthorizationLauncher = DefaultAuthorizationLauncher,
): CloudSession.Provider {
    val auth: CloudAuthManager = DropboxAuthManager(client)
    return CloudSession.Provider(
        clientId = BuildConfig.DROPBOX_APP_KEY,
        tokenStorage = tokenStorage,
        authManager = auth,
        connectFlow = OAuthConnectFlow(
            authManager = auth,
            clientId = BuildConfig.DROPBOX_APP_KEY,
            transport = CustomUriRedirectTransport(callbackFlow),
            authorizationLauncher = authorizationLauncher,
        ),
        createStorage = { tokenProvider -> DropboxStorage(client, tokenProvider) },
    )
}

/**
 * OneDrive: custom URI scheme (`keryx://`), shared with Dropbox and disambiguated by `state`.
 * Microsoft Identity platform is a PKCE public client, so no client secret is needed — shared by
 * desktop and Android for the same reason as [dropboxProvider].
 */
internal fun oneDriveProvider(
    client: HttpClient,
    callbackFlow: MutableSharedFlow<OAuthCallbackParams>,
    tokenStorage: TokenStorage,
    authorizationLauncher: AuthorizationLauncher = DefaultAuthorizationLauncher,
): CloudSession.Provider {
    val auth: CloudAuthManager = OneDriveAuthManager(client)
    return CloudSession.Provider(
        clientId = BuildConfig.ONEDRIVE_CLIENT_ID,
        tokenStorage = tokenStorage,
        authManager = auth,
        connectFlow = OAuthConnectFlow(
            authManager = auth,
            clientId = BuildConfig.ONEDRIVE_CLIENT_ID,
            transport = CustomUriRedirectTransport(callbackFlow),
            authorizationLauncher = authorizationLauncher,
        ),
        createStorage = { tokenProvider -> OneDriveStorage(client, tokenProvider) },
    )
}

/**
 * Registers the DI singles every platform's own `platformModule` shares: the OAuth callback flow
 * both `main.kt`'s (desktop) / `MainActivity`'s (Android) / the Apple app's own OS URI routing and
 * the custom-URI connect transport deliver into, and the [CloudSession] built over Dropbox +
 * OneDrive (both custom-URI providers, identical on every platform that offers them) plus whatever
 * [extraProviders] this platform alone supports.
 *
 * In `commonMain` (this function is called from `PlatformModule.desktop.kt`,
 * `PlatformModule.android.kt`, and appleMain's `ApplePlatformModule.kt` alike): [BuildConfig] (the
 * Dropbox/OneDrive client ids) is generated straight into `:shared`'s own `commonMain` (see
 * `shared/build.gradle.kts`'s `generatedBuildConfigDir` wiring), readable from every target
 * including Apple, which has no `jvmCommonMain`.
 *
 * @param tokenStorage Builds one secure-store instance per provider — called once per provider, so
 *   it must never be memoized across calls (see [TokenStorage]'s own "never share an instance
 *   across providers" rule).
 * @param extraProviders Providers that exist on this platform only (desktop's and the Apple app's
 *   own Google Drive clients; none on Android's `AuthorizationClient` path). The callback flow is
 *   handed in for an extra provider that also uses [CustomUriRedirectTransport] (the Apple app's
 *   Google Drive); desktop's loopback-based one simply ignores it. No default: a platform with none
 *   must say so explicitly (`{ _, _ -> emptyMap() }`) rather than silently omitting a provider slot.
 * @param authorizationLauncher How Dropbox/OneDrive's connect flow opens the authorize URL —
 *   the system browser on desktop/Android (the default), or the Apple app's own launcher (an
 *   `ASWebAuthenticationSession`, wired by Swift through [works.merc.keryx.app.sdk.KeryxSdk.start]).
 */
fun Module.cloudSessionSingles(
    tokenStorage: (CloudStorageType) -> TokenStorage,
    extraProviders: (client: HttpClient, callbackFlow: MutableSharedFlow<OAuthCallbackParams>) -> Map<CloudStorageType, CloudSession.Provider>,
    authorizationLauncher: AuthorizationLauncher = DefaultAuthorizationLauncher,
) {
    // Shared by each platform's own OS URI routing and the custom-URI (Dropbox/OneDrive) connect
    // transport.
    single<MutableSharedFlow<OAuthCallbackParams>> { MutableSharedFlow(replay = 0, extraBufferCapacity = 1) }

    single {
        val client = get<HttpClient>()
        val callbackFlow = get<MutableSharedFlow<OAuthCallbackParams>>()

        CloudSession(
            // Dropbox first, extras (e.g. desktop's Google Drive) in the middle, OneDrive last —
            // matching CloudStorageType's own declaration order, which drives the UI display order.
            providers = buildMap {
                put(
                    CloudStorageType.DROPBOX,
                    dropboxProvider(client, callbackFlow, tokenStorage(CloudStorageType.DROPBOX), authorizationLauncher),
                )
                putAll(extraProviders(client, callbackFlow))
                put(
                    CloudStorageType.ONEDRIVE,
                    oneDriveProvider(client, callbackFlow, tokenStorage(CloudStorageType.ONEDRIVE), authorizationLauncher),
                )
            },
            selectedType = {
                CloudStorageType.fromId(get<SettingsRepository>().getLocalSettings().cloudStorageType)
            },
            clock = get(),
            notificationCenter = get(),
        )
    }
}
