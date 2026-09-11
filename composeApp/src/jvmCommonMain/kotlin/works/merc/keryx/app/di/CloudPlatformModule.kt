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
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.CustomUriRedirectTransport
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
        ),
        createStorage = { tokenProvider -> OneDriveStorage(client, tokenProvider) },
    )
}

/**
 * Registers the DI singles every JVM platform's `platformModule` shares: the OAuth callback flow
 * both `main.kt`'s (desktop) / `MainActivity`'s (Android) own OS URI routing and the custom-URI
 * connect transport deliver into, and the [CloudSession] built over Dropbox + OneDrive (both
 * custom-URI providers, identical on every JVM target) plus whatever [extraProviders] this
 * platform alone supports.
 *
 * In `jvmCommonMain` rather than `commonMain`: [BuildConfig] (the Dropbox/OneDrive client
 * ids) is generated per-platform into this shared JVM source set (see
 * `composeApp/build.gradle.kts`'s `generatedBuildConfigDir` wiring), so it is not visible from
 * `commonMain`.
 *
 * @param tokenStorage Builds one secure-store instance per provider — called once per provider, so
 *   it must never be memoized across calls (see [TokenStorage]'s own "never share an instance
 *   across providers" rule).
 * @param extraProviders Providers that exist on this platform only (desktop's Google Drive; none
 *   on Android). No default: a platform with none must say so explicitly (`{ emptyMap() }`) rather
 *   than silently omitting a provider slot.
 */
internal fun Module.cloudSessionSingles(
    tokenStorage: (CloudStorageType) -> TokenStorage,
    extraProviders: (client: HttpClient) -> Map<CloudStorageType, CloudSession.Provider>,
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
                put(CloudStorageType.DROPBOX, dropboxProvider(client, callbackFlow, tokenStorage(CloudStorageType.DROPBOX)))
                putAll(extraProviders(client))
                put(CloudStorageType.ONEDRIVE, oneDriveProvider(client, callbackFlow, tokenStorage(CloudStorageType.ONEDRIVE)))
            },
            selectedType = {
                CloudStorageType.fromId(get<SettingsRepository>().getLocalSettings().cloudStorageType)
            },
            clock = get(),
            notificationCenter = get(),
            notificationMessages = get(),
        )
    }
}
