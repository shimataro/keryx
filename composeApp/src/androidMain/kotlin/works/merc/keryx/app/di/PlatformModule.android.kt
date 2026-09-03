package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.BuildConfig
import works.merc.keryx.app.core.CONNECTION_TIMEOUT_MS
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.REQUEST_TIMEOUT_MS
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.DropboxStorage
import works.merc.keryx.app.data.cloud.FileTokenStorage
import works.merc.keryx.app.data.cloud.KeystoreTokenStorage
import works.merc.keryx.app.data.cloud.OneDriveAuthManager
import works.merc.keryx.app.data.cloud.OneDriveStorage
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.CustomUriRedirectTransport
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OAuthConnectFlow
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.SettingsRepository
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
 * Android `platformModule`. Dropbox and OneDrive are wired the same way as desktop (PKCE public
 * client, `CustomUriRedirectTransport` over the shared `keryx://oauth2/callback` scheme — see
 * `.claude/rules/cloud-oauth-transport.md`); the OS delivers the redirect to `MainActivity`
 * (`AndroidManifest.xml`'s `keryx://oauth2/callback` intent-filter), which forwards it into
 * [callbackFlow] via `dispatchOAuthCallbackIfPresent`.
 *
 * Google Drive has no provider entry here — see `core/CloudStorageAvailability.android.kt`'s own
 * KDoc for why it is out of scope on this platform. `DatabaseMerger`/`DatabaseSnapshot` are fully
 * implemented (Android actuals), unlike the earlier phases' stubs.
 */
actual val platformModule: Module = module {
    single<OsNotificationSink> { AndroidNotificationSink(AndroidAppContext.application) }

    single<UpdateInstaller> { AndroidUpdateInstaller() }

    single {
        HttpClient(OkHttp) {
            // Statuses are handled explicitly everywhere (feed redirects, cloud errors).
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECTION_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }

    // Shared by MainActivity's OS URI routing (dispatchOAuthCallbackIfPresent) and the
    // custom-URI (Dropbox/OneDrive) connect transport — the Android counterpart of desktop's
    // PlatformModule.desktop.kt single of the same type.
    single { MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1) }

    single {
        val client = get<HttpClient>()
        val callbackFlow = get<MutableSharedFlow<OAuthCallbackParams>>()

        // Dropbox: custom URI scheme (keryx://) delivered by the OS.
        val dropboxAuth: CloudAuthManager = DropboxAuthManager(client)
        val dropboxProvider = CloudSession.Provider(
            clientId = BuildConfig.DROPBOX_APP_KEY,
            tokenStorage = providerTokenStorage(CloudStorageType.DROPBOX),
            authManager = dropboxAuth,
            connectFlow = OAuthConnectFlow(
                authManager = dropboxAuth,
                clientId = BuildConfig.DROPBOX_APP_KEY,
                transport = CustomUriRedirectTransport(callbackFlow),
            ),
            createStorage = { tokenProvider -> DropboxStorage(client, tokenProvider) },
        )

        // OneDrive: custom URI scheme (keryx://), shared with Dropbox and disambiguated by `state`.
        // Microsoft Identity platform is a PKCE public client, so no client secret is needed.
        val oneDriveAuth: CloudAuthManager = OneDriveAuthManager(client)
        val oneDriveProvider = CloudSession.Provider(
            clientId = BuildConfig.ONEDRIVE_CLIENT_ID,
            tokenStorage = providerTokenStorage(CloudStorageType.ONEDRIVE),
            authManager = oneDriveAuth,
            connectFlow = OAuthConnectFlow(
                authManager = oneDriveAuth,
                clientId = BuildConfig.ONEDRIVE_CLIENT_ID,
                transport = CustomUriRedirectTransport(callbackFlow),
            ),
            createStorage = { tokenProvider -> OneDriveStorage(client, tokenProvider) },
        )

        CloudSession(
            providers = mapOf(
                CloudStorageType.DROPBOX to dropboxProvider,
                CloudStorageType.ONEDRIVE to oneDriveProvider,
            ),
            selectedType = {
                CloudStorageType.fromId(get<SettingsRepository>().getLocalSettings().cloudStorageType)
            },
            clock = get(),
        )
    }
}
