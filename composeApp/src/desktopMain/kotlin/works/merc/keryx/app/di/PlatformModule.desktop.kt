package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.compose.resources.getString
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.BuildConfig
import works.merc.keryx.app.core.CONNECTION_TIMEOUT_MS
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.DropboxStorage
import works.merc.keryx.app.data.cloud.FileTokenStorage
import works.merc.keryx.app.data.cloud.GoogleDriveAuthManager
import works.merc.keryx.app.data.cloud.GoogleDriveStorage
import works.merc.keryx.app.data.cloud.KeyringTokenStorage
import works.merc.keryx.app.data.cloud.OneDriveAuthManager
import works.merc.keryx.app.data.cloud.OneDriveStorage
import works.merc.keryx.app.data.cloud.SecurityCliTokenStorage
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.CustomUriRedirectTransport
import works.merc.keryx.app.domain.LoopbackRedirectTransport
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OAuthConnectFlow
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.isMacOs
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.oauth_loopback_success

/**
 * One secure-store instance per provider (never shared — some impls cache the
 * loaded value). The account / file name is derived from [CloudStorageType.id];
 * Dropbox's values (`dropbox`) match the pre-multi-provider hardcoded ones, so no
 * migration is needed for existing users' stored tokens. macOS Keychain writes
 * fail from the shared JVM via java-keyring, so delegate to the Apple-signed
 * `security` CLI there; Windows/Linux keep the java-keyring backend.
 */
private fun providerTokenStorage(type: CloudStorageType, macOs: Boolean): TokenStorage {
    val fallback = FileTokenStorage(fileName = ".${type.id}_tokens.json")
    return if (macOs) SecurityCliTokenStorage(fallback = fallback, account = type.id)
    else KeyringTokenStorage(fallback = fallback, account = type.id)
}

actual val platformModule: Module = module {
    single {
        HttpClient(CIO) {
            // Statuses are handled explicitly everywhere (feed redirects, cloud errors).
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECTION_TIMEOUT_MS
                requestTimeoutMillis = 60_000
            }
        }
    }

    // Shared by main.kt's OS URI routing and the custom-URI (Dropbox) connect transport.
    single { MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1) }

    single {
        val client = get<HttpClient>()
        val callbackFlow = get<MutableSharedFlow<OAuthCallbackParams>>()

        // Dropbox: custom URI scheme (keryx://) delivered by the OS.
        val dropboxAuth: CloudAuthManager = DropboxAuthManager(client)
        val dropboxProvider = CloudSession.Provider(
            clientId = BuildConfig.DROPBOX_APP_KEY,
            tokenStorage = providerTokenStorage(CloudStorageType.DROPBOX, isMacOs),
            authManager = dropboxAuth,
            connectFlow = OAuthConnectFlow(
                authManager = dropboxAuth,
                clientId = BuildConfig.DROPBOX_APP_KEY,
                transport = CustomUriRedirectTransport(callbackFlow),
            ),
            createStorage = { tokenProvider -> DropboxStorage(client, tokenProvider) },
        )

        // Google Drive: loopback redirect (Google rejects arbitrary custom schemes).
        val driveAuth: CloudAuthManager = GoogleDriveAuthManager(client, BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET)
        val driveProvider = CloudSession.Provider(
            clientId = BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
            tokenStorage = providerTokenStorage(CloudStorageType.GOOGLE_DRIVE, isMacOs),
            authManager = driveAuth,
            connectFlow = OAuthConnectFlow(
                authManager = driveAuth,
                clientId = BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
                transport = LoopbackRedirectTransport(
                    successMessageProvider = { getString(Res.string.oauth_loopback_success) },
                ),
            ),
            createStorage = { tokenProvider -> GoogleDriveStorage(client, tokenProvider) },
        )

        // OneDrive: custom URI scheme (keryx://), shared with Dropbox and disambiguated by `state`.
        // Microsoft Identity platform is a PKCE public client, so no client secret is needed.
        val oneDriveAuth: CloudAuthManager = OneDriveAuthManager(client)
        val oneDriveProvider = CloudSession.Provider(
            clientId = BuildConfig.ONEDRIVE_CLIENT_ID,
            tokenStorage = providerTokenStorage(CloudStorageType.ONEDRIVE, isMacOs),
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
                CloudStorageType.GOOGLE_DRIVE to driveProvider,
                CloudStorageType.ONEDRIVE to oneDriveProvider,
            ),
            selectedType = {
                CloudStorageType.fromId(get<SettingsRepository>().getLocalSettings().cloudStorageType)
            },
            clock = get(),
        )
    }
}
